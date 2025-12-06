package top.xihale.unncm

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.documentfile.provider.DocumentFile
import androidx.activity.viewModels
import androidx.lifecycle.Observer
import top.xihale.unncm.databinding.ActivityMainBinding
import top.xihale.unncm.utils.Logger
import com.google.android.material.color.DynamicColors
import java.io.File

import androidx.core.view.WindowCompat
import android.graphics.Color
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : AppCompatActivity() {

    private val logger = Logger.withTag("MainActivity")

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: FileAdapter

    private val INPUT_FOLDER_REQUEST_CODE = 101

    private val openDocumentTreeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        logger.d("Folder picker result received: $uri")
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                val docFile = DocumentFile.fromTreeUri(this, uri)
                logger.i("Selected folder: ${docFile?.name}, URI: $uri, isDirectory: ${docFile?.isDirectory}")

                if (docFile != null && docFile.isDirectory) {
                    if (currentFolderRequestCode == INPUT_FOLDER_REQUEST_CODE) {
                        logger.i("Setting input folder: $uri")

                        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
                        prefs.edit().putString("input_uri", uri.toString()).apply()

                        viewModel.setInputDocumentFileSync(docFile)
                        // Explicit scan when user selects a folder
                        viewModel.scanFiles()
                    } else {
                        logger.w("Invalid request code: $currentFolderRequestCode")
                    }
                } else {
                    logger.w("Invalid directory selected: $uri")
                    Toast.makeText(this, getString(R.string.msg_invalid_directory), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                logger.e("Error handling folder selection", e)
                Toast.makeText(this, "Error setting folder: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            logger.d("Folder picker cancelled")
        }
    }

    private var currentFolderRequestCode: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        logger.i("MainActivity onCreate started")
        super.onCreate(savedInstanceState)
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = Color.TRANSPARENT
            DynamicColors.applyToActivityIfAvailable(this)
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            logger.d("UI setup completed")

            adapter = FileAdapter(mutableListOf())
            binding.recyclerView.layoutManager = LinearLayoutManager(this)
            binding.recyclerView.adapter = adapter
            logger.d("RecyclerView adapter initialized")

            setupButtons()
            setupViewModelObservers()

            // Restore path from SharedPreferences and setup UI
            logger.d("Attempting to restore path from prefs")
            val pathSet = setupDefaultPaths()
            if (pathSet) {
                logger.d("Path restored successfully from prefs")
                // Since setupDefaultPaths already set the inputDocumentFile, check if we need to trigger scan
                if (viewModel.pendingFiles.value.isNullOrEmpty()) {
                    logger.d("No pending files found after path restoration, triggering scan")
                    viewModel.scanFiles()
                }
            } else {
                // No path set, prompt user to select folder
                Toast.makeText(this, getString(R.string.msg_select_input_dir), Toast.LENGTH_LONG).show()
                openFolderPicker(INPUT_FOLDER_REQUEST_CODE)
            }

            logger.i("MainActivity onCreate completed successfully")
        } catch (e: Exception) {
            logger.e("Error in MainActivity onCreate", e)
            Toast.makeText(this, "Initialization failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupViewModelObservers() {
        // Observe pending files changes
        viewModel.pendingFiles.observe(this, Observer { files ->
            adapter.updateList(files)
            binding.tvStatus.text = files.size.toString()
            logger.d("Pending files updated in UI: ${files.size} files")
        })

        // Observe input document file changes
        viewModel.inputDocumentFile.observe(this, Observer { docFile ->
            if (docFile != null) {
                binding.inputFolderEditText.setText(docFile.uri.toString())
                logger.d("Input document file updated in UI: ${docFile.name}")
            }
        })

        // Observe scanning state
        viewModel.isScanning.observe(this, Observer { isScanning ->
            if (isScanning) {
                binding.tvStatus.text = getString(R.string.status_scanning)
            } else {
                val count = viewModel.pendingFiles.value?.size ?: 0
                binding.tvStatus.text = count.toString()
            }
            logger.d("Scanning state updated: $isScanning")
        })

        // Observe conversion status
        viewModel.conversionStatus.observe(this, Observer { state ->
            when (state) {
                is ConversionUiState.Idle -> {
                    binding.btnConvert.isEnabled = true
                    binding.btnConvert.text = getString(R.string.convert_button)
                }
                is ConversionUiState.Converting -> {
                    binding.btnConvert.isEnabled = false
                    binding.btnConvert.text = getString(R.string.status_converting)
                }
                is ConversionUiState.Completed -> {
                    binding.btnConvert.isEnabled = true
                    binding.btnConvert.text = getString(R.string.convert_button)
                    if (state.errorCount == 0) {
                        Toast.makeText(this, getString(R.string.msg_convert_complete), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, getString(R.string.msg_convert_error), Toast.LENGTH_SHORT).show()
                    }
                    viewModel.resetConversionStatus()
                }
                is ConversionUiState.Error -> {
                    binding.btnConvert.isEnabled = true
                    binding.btnConvert.text = getString(R.string.convert_button)
                    Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
                    viewModel.resetConversionStatus()
                }
            }
        })
    }

    private fun setupDefaultPaths(): Boolean {
        logger.d("Setting up default paths")
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val savedInputUri = prefs.getString("input_uri", null)
        val savedInputPath = prefs.getString("input_path", null) // Fallback for legacy

        logger.d("Saved input URI: $savedInputUri, saved input path: $savedInputPath")

        if (savedInputUri != null) {
            logger.i("Restoring input folder from saved URI")
            val uri = Uri.parse(savedInputUri)
            try {
                val persistedUriPermissions = contentResolver.persistedUriPermissions
                val hasPermission = persistedUriPermissions.any { it.uri == uri }

                if (!hasPermission) {
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    } catch (e: SecurityException) {
                        logger.e("Failed to restore persistent permission", e)
                        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
                        prefs.edit().remove("input_uri").apply()
                        return false
                    }
                }

                val docFile = DocumentFile.fromTreeUri(this, uri)
                if (docFile != null && docFile.isDirectory && docFile.exists()) {
                    viewModel.setInputDocumentFileSync(docFile)
                    logger.i("Successfully restored input folder: ${docFile.name}")
                    return true
                } else {
                    logger.w("DocumentFile validation failed - docFile: ${docFile != null}, isDirectory: ${docFile?.isDirectory}, exists: ${docFile?.exists()}")
                    // Clear invalid URI
                    val prefs = getSharedPreferences("settings", MODE_PRIVATE)
                    prefs.edit().remove("input_uri").apply()
                }
            } catch (e: Exception) {
                logger.w("Failed to restore saved input URI", e)
            }
        } else if (savedInputPath != null) {
            logger.i("Restoring input folder from saved path (legacy)")
            val file = File(savedInputPath)
            if (file.exists() && file.isDirectory) {
                val docFile = DocumentFile.fromFile(file)
                viewModel.setInputDocumentFileSync(docFile)
                logger.i("Successfully restored input path: $savedInputPath")
                return true
            } else {
                logger.w("Legacy input path does not exist or is not a directory: $savedInputPath")
                // Clear invalid legacy path
                val prefs = getSharedPreferences("settings", MODE_PRIVATE)
                prefs.edit().remove("input_path").apply()
            }
        }

        return false
    }

    private fun setupButtons() {
        logger.d("Setting up button listeners")

        binding.inputFolderLayout.setEndIconOnClickListener {
            logger.d("Input folder button clicked")
            openFolderPicker(INPUT_FOLDER_REQUEST_CODE)
        }

        binding.seekBarThreads.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvThreadLabel.text = getString(R.string.label_threads, progress + 1)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnConvert.setOnClickListener {
            logger.d("Convert button clicked")
            val pendingFilesList = viewModel.pendingFiles.value
            if (!pendingFilesList.isNullOrEmpty()) {
                logger.i("Starting conversion of ${pendingFilesList.size} files")
                val maxThreads = binding.seekBarThreads.progress + 1
                viewModel.convertFiles(maxThreads, cacheDir)
            } else {
                logger.w("No files to convert")
                Toast.makeText(this, getString(R.string.no_files_found), Toast.LENGTH_SHORT).show()
            }
        }

        logger.d("Button setup completed")
    }

    private fun openFolderPicker(requestCode: Int) {
        currentFolderRequestCode = requestCode
        logger.d("Opening folder picker with request code: $requestCode")
        
        var initialUri: Uri? = null
        if (requestCode == INPUT_FOLDER_REQUEST_CODE && viewModel.inputDocumentFile.value == null) {
             try {
                 initialUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2Fnetease%2Fcloudmusic%2FMusic")
             } catch (e: Exception) {
                 // Ignore
             }
        }
        
        openDocumentTreeLauncher.launch(initialUri)
    }
}