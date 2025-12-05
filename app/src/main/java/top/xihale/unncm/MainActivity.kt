package top.xihale.unncm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import top.xihale.unncm.databinding.ActivityMainBinding
import top.xihale.unncm.R
import com.google.android.material.color.DynamicColors
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.OutputStream

import androidx.core.view.WindowCompat
import android.graphics.Color
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var inputDocumentFile: DocumentFile? = null
    private var outputDocumentFile: DocumentFile? = null
    private val pendingFiles = mutableListOf<UiFile>()
    private lateinit var adapter: FileAdapter

    private val INPUT_FOLDER_REQUEST_CODE = 101
    private val OUTPUT_FOLDER_REQUEST_CODE = 102

    private val openDocumentTreeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val docFile = DocumentFile.fromTreeUri(this, uri)
            if (docFile != null && docFile.isDirectory) {
                val prefs = getSharedPreferences("settings", MODE_PRIVATE)
                when (currentFolderRequestCode) {
                    INPUT_FOLDER_REQUEST_CODE -> {
                        binding.inputFolderEditText.setText(uri.toString())
                        prefs.edit().putString("input_uri", uri.toString()).apply()
                        inputDocumentFile = docFile
                        scanFiles()

                        // Automatically update output dir to input/unlocked
                        val unlockedDir = docFile.findFile("unlocked") 
                                            ?: docFile.createDirectory("unlocked")

                        if (unlockedDir != null) {
                            outputDocumentFile = unlockedDir
                            binding.outputFolderEditText.setText(unlockedDir.uri.toString())
                            prefs.edit().putString("output_uri", unlockedDir.uri.toString()).apply()
                        } else {
                            Toast.makeText(this, "Could not create 'unlocked' directory in input folder", Toast.LENGTH_LONG).show()
                        }
                    }
                    OUTPUT_FOLDER_REQUEST_CODE -> {
                        binding.outputFolderEditText.setText(uri.toString())
                        prefs.edit().putString("output_uri", uri.toString()).apply()
                        outputDocumentFile = docFile
                    }
                }
            } else {
                 Toast.makeText(this, "Invalid directory selected", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val manageAllFilesAccessLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                scanFiles()
            } else {
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            scanFiles()
        } else {
            Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private var currentFolderRequestCode: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        DynamicColors.applyToActivityIfAvailable(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = FileAdapter(pendingFiles)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        setupDefaultPaths()
        setupButtons()
        
        if (!checkPermissions()) {
            requestPermissions()
        } else {
            scanFiles() 
        }
    }

    private fun setupDefaultPaths() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val savedInputUri = prefs.getString("input_uri", null)
        val savedOutputUri = prefs.getString("output_uri", null)
        val savedInputPath = prefs.getString("input_path", null) // Fallback for legacy

        if (savedInputUri != null) {
            val uri = Uri.parse(savedInputUri)
            try {
                val docFile = DocumentFile.fromTreeUri(this, uri)
                if (docFile != null && docFile.isDirectory) {
                    inputDocumentFile = docFile
                    binding.inputFolderEditText.setText(savedInputUri)
                }
            } catch (e: Exception) {
                // URI permission might be lost or invalid
            }
        } else if (savedInputPath != null) {
            val file = File(savedInputPath)
            if (file.exists()) {
                inputDocumentFile = DocumentFile.fromFile(file)
                binding.inputFolderEditText.setText(savedInputPath)
            }
        } else {
             // Default Input Path
             val defaultInputPath = File(Environment.getExternalStorageDirectory(), "Download/netease/cloudmusic/Music")
             if (defaultInputPath.exists()) {
                 inputDocumentFile = DocumentFile.fromFile(defaultInputPath)
                 binding.inputFolderEditText.setText(defaultInputPath.absolutePath)
             }

             // Default Output Path
             val defaultOutputPath = File(Environment.getExternalStorageDirectory(), "Download/netease/cloudmusic/Music/unlocked")
             if (defaultOutputPath.exists()) {
                 outputDocumentFile = DocumentFile.fromFile(defaultOutputPath)
                 binding.outputFolderEditText.setText(defaultOutputPath.absolutePath)
             } else {
                 // Try to create the default output directory if it doesn't exist
                 if (defaultOutputPath.mkdirs()) {
                     outputDocumentFile = DocumentFile.fromFile(defaultOutputPath)
                     binding.outputFolderEditText.setText(defaultOutputPath.absolutePath)
                 }
             }
        }

        if (savedOutputUri != null) {
             val uri = Uri.parse(savedOutputUri)
             try {
                 val docFile = DocumentFile.fromTreeUri(this, uri)
                 if (docFile != null) {
                     outputDocumentFile = docFile
                     binding.outputFolderEditText.setText(savedOutputUri)
                 }
             } catch (e: Exception) {}
        }
    }

    private fun setupButtons() {
        binding.inputFolderLayout.setEndIconOnClickListener {
            openFolderPicker(INPUT_FOLDER_REQUEST_CODE)
        }

        binding.outputFolderLayout.setEndIconOnClickListener {
            openFolderPicker(OUTPUT_FOLDER_REQUEST_CODE)
        }

        binding.seekBarThreads.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvThreadLabel.text = "Threads: ${progress + 1}"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnConvert.setOnClickListener {
            if (checkPermissions()) {
                if (pendingFiles.isNotEmpty()) {
                    convertFiles()
                } else {
                    Toast.makeText(this, getString(R.string.no_files_found), Toast.LENGTH_SHORT).show()
                }
            } else {
                requestPermissions()
            }
        }
    }

    private fun openFolderPicker(requestCode: Int) {
        currentFolderRequestCode = requestCode
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        openDocumentTreeLauncher.launch(null)
    }

    private fun scanFiles() {
        // If user typed a path manually, try to resolve it to a File and then DocumentFile
        val inputPathText = binding.inputFolderEditText.text.toString()
        
        if (inputPathText.isBlank()) {
            Toast.makeText(this, "Please select input directory", Toast.LENGTH_SHORT).show()
            return
        }

        // If inputDocumentFile is null or doesn't match the text (user edited text), try to create from File
        if (inputDocumentFile == null || (inputDocumentFile?.uri?.toString() != inputPathText && inputDocumentFile?.name != inputPathText)) {
             // Try treating as a path
             val f = File(inputPathText)
             if (f.exists() && f.isDirectory) {
                 inputDocumentFile = DocumentFile.fromFile(f)
             } else if (inputDocumentFile == null) {
                 // Maybe it's a URI string typed in?
                 try {
                     val uri = Uri.parse(inputPathText)
                     val d = DocumentFile.fromTreeUri(this, uri)
                     if (d != null && d.isDirectory) inputDocumentFile = d
                 } catch (e: Exception) {}
             }
        }

        if (inputDocumentFile == null || !inputDocumentFile!!.exists()) {
            Toast.makeText(this, "Invalid input directory", Toast.LENGTH_SHORT).show()
            return
        }

        // Output logic
        val outputPathText = binding.outputFolderEditText.text.toString()
        if (outputPathText.isNotBlank()) {
             if (outputDocumentFile == null || outputDocumentFile?.uri?.toString() != outputPathText) {
                 val f = File(outputPathText)
                 if (f.exists()) {
                     outputDocumentFile = DocumentFile.fromFile(f)
                 } else {
                     // Try to create dir? Standard File API
                     if (!f.exists() && f.mkdirs()) {
                          outputDocumentFile = DocumentFile.fromFile(f)
                     }
                 }
             }
        }
        
        // Fallback output to "unlocked" inside input
        if (outputDocumentFile == null) {
             // Creating "unlocked" directory in inputDocumentFile
             val unlocked = inputDocumentFile!!.findFile("unlocked") 
                            ?: inputDocumentFile!!.createDirectory("unlocked")
             outputDocumentFile = unlocked
        }

        binding.tvStatus.text = "Scanning..."
        
        CoroutineScope(Dispatchers.IO).launch {
            val files = inputDocumentFile!!.listFiles()
            val ncmFiles = files.filter { 
                it.isFile && it.name?.endsWith(".ncm", ignoreCase = true) == true 
            }

            val outputFiles = outputDocumentFile?.listFiles()?.mapNotNull { it.name?.substringBeforeLast('.') }?.toSet() ?: emptySet()
            
            val toProcess = ncmFiles.filter { 
                it.name?.substringBeforeLast('.') !in outputFiles 
            }

            withContext(Dispatchers.Main) {
                pendingFiles.clear()
                pendingFiles.addAll(toProcess.map { UiFile(it, FileStatus.PENDING) })
                adapter.updateList(pendingFiles)
                binding.tvStatus.text = pendingFiles.size.toString()
            }
        }
    }

    private fun convertFiles() {
        binding.btnConvert.isEnabled = false
        
        if (outputDocumentFile == null || !outputDocumentFile!!.exists()) {
             // Try to recreate
             val unlocked = inputDocumentFile!!.findFile("unlocked") 
                            ?: inputDocumentFile!!.createDirectory("unlocked")
             outputDocumentFile = unlocked
        }
        
        if (outputDocumentFile == null) {
            Toast.makeText(this, "Cannot create output directory", Toast.LENGTH_SHORT).show()
             binding.btnConvert.isEnabled = true
            return
        }

        val maxThreads = binding.seekBarThreads.progress + 1
        
        CoroutineScope(Dispatchers.IO).launch {
            // Use a copy to iterate safely while modifying the original list
            val filesToConvert = ArrayList(pendingFiles)
            val semaphore = Semaphore(maxThreads)
            
            val deferreds = filesToConvert.map { uiFile ->
                async(Dispatchers.IO) {
                    // Skip if already done
                    if (uiFile.status == FileStatus.DONE) return@async

                    semaphore.withPermit {
                        try {
                            val docFile = uiFile.documentFile
                            var success = false
                            
                            val inputStream = contentResolver.openInputStream(docFile.uri)
                            if (inputStream != null) {
                                inputStream.use { input ->
                                    val decryptor = NcmStreamDecryptor(input)
                                    val ncmInfo = decryptor.parseHeader()
                                    
                                    if (ncmInfo != null) {
                                        val ext = ncmInfo.format
                                        val baseName = docFile.name?.substringBeforeLast('.') ?: "unknown"
                                        val fileName = "$baseName.$ext"

                                        val outFile = outputDocumentFile!!.createFile("audio/*", fileName)
                                        
                                        if (outFile != null) {
                                            contentResolver.openOutputStream(outFile.uri)?.use { output ->
                                                decryptor.decryptAudioStream(output)
                                            }
                                            success = true
                                        }
                                    }
                                }
                            }
                            
                            withContext(Dispatchers.Main) {
                                if (success) {
                                    uiFile.status = FileStatus.DONE 
                                    pendingFiles.remove(uiFile)
                                    adapter.removeItem(uiFile)
                                    // Update remaining count
                                    binding.tvStatus.text = "Remaining: ${pendingFiles.size}"
                                } else {
                                    uiFile.status = FileStatus.ERROR
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            withContext(Dispatchers.Main) {
                                 uiFile.status = FileStatus.ERROR
                            }
                        }
                    }
                }
            }
            
            deferreds.awaitAll()

            withContext(Dispatchers.Main) {
                binding.btnConvert.isEnabled = true
                if (pendingFiles.isEmpty()) {
                    Toast.makeText(this@MainActivity, "All files converted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Finished with errors", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun checkPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager()
        } else {
            val read = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            val write = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return read == PackageManager.PERMISSION_GRANTED && write == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.addCategory("android.intent.category.DEFAULT")
                intent.data = Uri.parse(String.format("package:%s", applicationContext.packageName))
                manageAllFilesAccessLauncher.launch(intent)
            } catch (e: Exception) {
                val intent = Intent()
                intent.action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                manageAllFilesAccessLauncher.launch(intent)
            }
        } else {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
    }
}