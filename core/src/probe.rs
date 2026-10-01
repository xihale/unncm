//! 轻量音频元数据探针：只为扫描阶段判断"标题/歌手/封面是否齐全"，
//! 不解析帧内容（只看帧 ID 是否存在），因此无需把封面数据读入内存。

use std::io::{Read, Seek, SeekFrom};

pub const HAS_TITLE: u32 = 1;
pub const HAS_ARTIST: u32 = 2;
pub const HAS_COVER: u32 = 4;
pub const METADATA_COMPLETE: u32 = HAS_TITLE | HAS_ARTIST | HAS_COVER;

/// 允许遍历的标签区上限，防止损坏文件导致超长读取。
const MAX_TAG_SCAN: u64 = 32 * 1024 * 1024;

/// 无法识别的格式返回 0（= 缺元数据），让上层按"待处理"兜底。
pub fn probe_metadata<R: Read + Seek>(reader: &mut R) -> u32 {
    let mut magic = [0u8; 4];
    if reader.read_exact(&mut magic).is_err() {
        return 0;
    }
    if reader.seek(SeekFrom::Start(0)).is_err() {
        return 0;
    }
    match magic {
        [b'I', b'D', b'3', version, ..] if version == 2 || version == 3 || version == 4 => {
            probe_id3v2(reader)
        }
        [b'f', b'L', b'a', b'C'] => probe_flac(reader),
        _ => 0,
    }
}

/// 遍历 ID3v2 帧头（v2.2 的 3 字节 ID / v2.3+ 的 4 字节 ID），按帧 ID 判断标签存在性。
fn probe_id3v2<R: Read + Seek>(reader: &mut R) -> u32 {
    let mut header = [0u8; 10];
    if reader.read_exact(&mut header).is_err() {
        return 0;
    }
    let version = header[3];
    let flags = header[5];
    // 非同步标签的帧大小被打散，逐帧走会错位；宁可保守返回"缺元数据"。
    if flags & 0x80 != 0 {
        return 0;
    }
    let tag_size = u64::from(syncsafe(&header[6..10]));
    let tag_end = 10 + tag_size.min(MAX_TAG_SCAN);

    let mut pos: u64 = 10;
    if flags & 0x40 != 0 {
        // 扩展头：v2.4 的长度字段含自身，v2.3 不含
        let mut size_buf = [0u8; 4];
        if reader
            .seek(SeekFrom::Start(pos))
            .and_then(|_| reader.read_exact(&mut size_buf))
            .is_err()
        {
            return 0;
        }
        let ext_size = u64::from(if version == 4 {
            syncsafe(&size_buf)
        } else {
            be32(&size_buf)
        });
        pos += if version == 4 { ext_size } else { 4 + ext_size };
    }

    let id_len: usize = if version == 2 { 3 } else { 4 };
    let header_len: u64 = if version == 2 { 6 } else { 10 };

    let mut found = 0u32;
    while pos + header_len <= tag_end {
        let mut frame = [0u8; 10];
        if reader
            .seek(SeekFrom::Start(pos))
            .and_then(|_| reader.read_exact(&mut frame))
            .is_err()
        {
            break;
        }
        if frame[0] == 0 {
            break; // 进入 padding 区
        }
        let frame_size: u64 = if version == 2 {
            u64::from(be24(&frame[3..6]))
        } else if version == 3 {
            u64::from(be32(&frame[4..8]))
        } else {
            u64::from(syncsafe(&frame[4..8]))
        };
        if frame_size == 0 || pos + header_len + frame_size > tag_end {
            break; // 尺寸异常视为标签损坏
        }

        found |= match &frame[0..id_len] {
            b"TIT2" | b"TT2" => HAS_TITLE,
            b"TPE1" | b"TP1" => HAS_ARTIST,
            b"APIC" | b"PIC" => HAS_COVER,
            _ => 0,
        };
        if found == METADATA_COMPLETE {
            return found;
        }
        pos += header_len + frame_size;
    }
    found
}

/// 走 FLAC metadata block：块 4（VORBIS_COMMENT）里找 TITLE/ARTIST，块 6（PICTURE）即有封面。
fn probe_flac<R: Read + Seek>(reader: &mut R) -> u32 {
    let mut block_start: u64 = 4;
    let mut found = 0u32;
    loop {
        if reader.seek(SeekFrom::Start(block_start)).is_err() {
            break;
        }
        let mut header = [0u8; 4];
        if reader.read_exact(&mut header).is_err() {
            break;
        }
        let is_last = header[0] & 0x80 != 0;
        let block_type = header[0] & 0x7f;
        let block_len = u64::from(be24(&header[1..4]));

        match block_type {
            4 => found |= scan_vorbis_comments(reader, block_len),
            6 => found |= HAS_COVER,
            _ => {}
        }

        if found == METADATA_COMPLETE || is_last || block_len > MAX_TAG_SCAN {
            break;
        }
        block_start += 4 + block_len;
    }
    found
}

/// 在 VORBIS_COMMENT 块内找 TITLE=/ARTIST= 字段名（字段名大小写不敏感）。
fn scan_vorbis_comments<R: Read>(reader: &mut R, block_len: u64) -> u32 {
    if block_len == 0 || block_len > MAX_TAG_SCAN {
        return 0;
    }
    let mut buf = vec![0u8; block_len as usize];
    if reader.read_exact(&mut buf).is_err() {
        return 0;
    }

    fn read_le32(buf: &[u8], cursor: &mut usize) -> Option<u32> {
        let end = *cursor + 4;
        if end > buf.len() {
            return None;
        }
        let value = u32::from_le_bytes([buf[*cursor], buf[*cursor + 1], buf[*cursor + 2], buf[*cursor + 3]]);
        *cursor = end;
        Some(value)
    }

    let mut found = 0u32;
    let mut cursor = 0usize;
    let Some(vendor_len) = read_le32(&buf, &mut cursor).map(|v| v as usize) else {
        return 0;
    };
    let Some(count) = cursor
        .checked_add(vendor_len)
        .and_then(|offset| {
            cursor = offset;
            read_le32(&buf, &mut cursor)
        })
    else {
        return 0;
    };
    if count > 4096 {
        return found;
    }

    for _ in 0..count {
        let comment_len = match read_le32(&buf, &mut cursor) {
            Some(len) => len as usize,
            None => break,
        };
        if cursor + comment_len > buf.len() {
            break;
        }
        let comment = &buf[cursor..cursor + comment_len];
        cursor += comment_len;

        if let Some(eq) = comment.iter().position(|&b| b == b'=') {
            let key: Vec<u8> = comment[..eq].iter().map(|b| b.to_ascii_uppercase()).collect();
            match key.as_slice() {
                b"TITLE" => found |= HAS_TITLE,
                b"ARTIST" => found |= HAS_ARTIST,
                _ => {}
            }
        }
        if found == HAS_TITLE | HAS_ARTIST {
            break;
        }
    }
    found
}

fn syncsafe(bytes: &[u8]) -> u32 {
    ((bytes[0] as u32 & 0x7f) << 21)
        | ((bytes[1] as u32 & 0x7f) << 14)
        | ((bytes[2] as u32 & 0x7f) << 7)
        | (bytes[3] as u32 & 0x7f)
}

fn be32(bytes: &[u8]) -> u32 {
    u32::from_be_bytes([bytes[0], bytes[1], bytes[2], bytes[3]])
}

fn be24(bytes: &[u8]) -> u32 {
    ((bytes[0] as u32) << 16) | ((bytes[1] as u32) << 8) | bytes[2] as u32
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    fn push_frame_v23(buf: &mut Vec<u8>, id: &[u8; 4], content: &[u8]) {
        buf.extend_from_slice(id);
        buf.extend_from_slice(&(content.len() as u32).to_be_bytes());
        buf.extend_from_slice(&[0, 0]);
        buf.extend_from_slice(content);
    }

    fn push_frame_v24(buf: &mut Vec<u8>, id: &[u8; 4], content: &[u8]) {
        let size = content.len() as u32;
        let syncsafe = [
            ((size >> 21) & 0x7f) as u8,
            ((size >> 14) & 0x7f) as u8,
            ((size >> 7) & 0x7f) as u8,
            (size & 0x7f) as u8,
        ];
        buf.extend_from_slice(id);
        buf.extend_from_slice(&syncsafe);
        buf.extend_from_slice(&[0, 0]);
        buf.extend_from_slice(content);
    }

    fn id3_header(version: u8, tag_size: usize) -> Vec<u8> {
        let mut header = vec![b'I', b'D', b'3', version, 0, 0];
        let size = tag_size as u32;
        header.extend_from_slice(&[
            ((size >> 21) & 0x7f) as u8,
            ((size >> 14) & 0x7f) as u8,
            ((size >> 7) & 0x7f) as u8,
            (size & 0x7f) as u8,
        ]);
        header
    }

    #[test]
    fn id3v23_with_title_artist_cover_is_complete() {
        let mut frames = Vec::new();
        push_frame_v23(&mut frames, b"TIT2", b"Song");
        push_frame_v23(&mut frames, b"TPE1", b"Artist");
        push_frame_v23(&mut frames, b"TALB", b"Album");
        push_frame_v23(&mut frames, b"APIC", &[0, b'i', b'm', b'g']);

        let mut data = id3_header(3, frames.len());
        data.extend_from_slice(&frames);
        assert_eq!(probe_metadata(&mut Cursor::new(data)), METADATA_COMPLETE);
    }

    #[test]
    fn id3v24_missing_cover_reports_partial() {
        let mut frames = Vec::new();
        push_frame_v24(&mut frames, b"TIT2", b"Song");
        push_frame_v24(&mut frames, b"TPE1", b"Artist");

        let mut data = id3_header(4, frames.len());
        data.extend_from_slice(&frames);
        assert_eq!(probe_metadata(&mut Cursor::new(data)), HAS_TITLE | HAS_ARTIST);
    }

    #[test]
    fn id3v22_frames_are_recognized() {
        let mut frames: Vec<u8> = Vec::new();
        frames.extend_from_slice(b"TT2");
        frames.extend_from_slice(&[0, 0, 4]);
        frames.extend_from_slice(b"Song");
        frames.extend_from_slice(b"PIC");
        frames.extend_from_slice(&[0, 0, 4]);
        frames.extend_from_slice(&[0, b'j', b'p', b'g']);

        let mut data = id3_header(2, frames.len());
        data.extend_from_slice(&frames);
        assert_eq!(probe_metadata(&mut Cursor::new(data)), HAS_TITLE | HAS_COVER);
    }

    #[test]
    fn corrupt_frame_size_stops_the_walk() {
        let mut frames = Vec::new();
        push_frame_v23(&mut frames, b"TIT2", b"Song");
        frames.extend_from_slice(b"TPE1");
        frames.extend_from_slice(&[0xff, 0xff, 0xff, 0xff]); // 巨大异常尺寸
        frames.extend_from_slice(&[0, 0]);

        let mut data = id3_header(3, frames.len() + 64);
        data.extend_from_slice(&frames);
        data.extend_from_slice(&[0u8; 64]);
        assert_eq!(probe_metadata(&mut Cursor::new(data)), HAS_TITLE);
    }

    fn flac_block(block_type: u8, content: &[u8], is_last: bool) -> Vec<u8> {
        let mut block = vec![(if is_last { 0x80 } else { 0x00 }) | block_type];
        let len = content.len() as u32;
        block.extend_from_slice(&[(len >> 16) as u8, (len >> 8) as u8, len as u8]);
        block.extend_from_slice(content);
        block
    }

    fn vorbis_comment_block(comments: &[&str]) -> Vec<u8> {
        let mut content = Vec::new();
        let vendor = b"test";
        content.extend_from_slice(&(vendor.len() as u32).to_le_bytes());
        content.extend_from_slice(vendor);
        content.extend_from_slice(&(comments.len() as u32).to_le_bytes());
        for comment in comments {
            content.extend_from_slice(&(comment.len() as u32).to_le_bytes());
            content.extend_from_slice(comment.as_bytes());
        }
        content
    }

    #[test]
    fn flac_with_picture_block_is_complete() {
        let mut data = vec![b'f', b'L', b'a', b'C'];
        data.extend(flac_block(0, &[0u8; 34], false));
        data.extend(flac_block(4, &vorbis_comment_block(&["ARTIST=Artist", "TITLE=Song"]), false));
        data.extend(flac_block(6, &[0u8; 8], true));
        assert_eq!(probe_metadata(&mut Cursor::new(data)), METADATA_COMPLETE);
    }

    #[test]
    fn flac_without_picture_block_reports_partial() {
        let mut data = vec![b'f', b'L', b'a', b'C'];
        data.extend(flac_block(4, &vorbis_comment_block(&["TITLE=Song", "ARTIST=Artist"]), true));
        assert_eq!(probe_metadata(&mut Cursor::new(data)), HAS_TITLE | HAS_ARTIST);
    }

    #[test]
    fn unknown_format_reports_nothing() {
        assert_eq!(probe_metadata(&mut Cursor::new(b"OggS0000".to_vec())), 0);
        assert_eq!(probe_metadata(&mut Cursor::new(Vec::new())), 0);
    }
}
