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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import top.xihale.unncm.databinding.ActivityMainBinding
import top.xihale.unncm.utils.Logger
import com.google.android.material.color.DynamicColors
import java.io.File

import androidx.core.view.WindowCompat
import android.graphics.Color
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit

class MainActivity : AppCompatActivity() {

    private val logger = Logger.withTag("MainActivity")

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: FileAdapter

    private val INPUT_FOLDER_REQUEST_CODE = 101

    private val openDocumentTreeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        logger.d("Folder picker result received: $uri")
        if (uri == null) {
            logger.d("Folder picker cancelled")
            return@registerForActivityResult
        }

        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

            val inputDir = DocumentFile.fromTreeUri(this, uri)
            logger.i("Selected folder: ${inputDir?.name}, URI: $uri, isDirectory: ${inputDir?.isDirectory}")

            if (inputDir == null) {
                logger.w("DocumentFile.fromTreeUri returned null for URI: $uri")
                return@registerForActivityResult
            }

            if (currentFolderRequestCode != INPUT_FOLDER_REQUEST_CODE) {
                logger.w("Invalid request code: $currentFolderRequestCode")
                return@registerForActivityResult
            }

            logger.i("Setting input folder: $uri")

            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
            prefs.edit { putString("input_uri", uri.toString()) }

            viewModel.setInputDir(inputDir)

            viewModel.scanFiles()
        } catch (e: Exception) {
            logger.e("Error handling folder selection", e)
            Toast.makeText(this, "Error setting folder: ${e.message}", Toast.LENGTH_SHORT).show()
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
        // Observe pending files changes (StateFlow)
        lifecycleScope.launch {
            viewModel.pendingFiles.collect { files ->
                adapter.updateList(files)
                // Only update status text if we are not currently scanning (Converting state uses button for status)
                val currentState = viewModel.conversionStatus.value
                if (currentState !is ConversionUiState.Scanning) {
                    binding.tvStatus.text = files.size.toString()
                }
                logger.d("Pending files updated in UI: ${files.size} files")
            }
        }

        // Observe input document file changes
        viewModel.inputDir.observe(this, Observer { docFile ->
            docFile?.let {
                binding.inputFolderEditText.setText(it.uri.toString())
                logger.d("Input document file updated in UI: ${it.name}")
            }
        })

        // Observe conversion status
        viewModel.conversionStatus.observe(this, Observer { state ->
            when (state) {
                is ConversionUiState.Idle -> {
                    binding.btnConvert.isEnabled = true
                    binding.btnConvert.text = getString(R.string.convert_button)
                    val count = viewModel.pendingFiles.value?.size ?: 0
                    binding.tvStatus.text = count.toString()
                }
                is ConversionUiState.Scanning -> {
                    binding.btnConvert.isEnabled = false
                    binding.tvStatus.text = getString(R.string.status_scanning)
                }
                is ConversionUiState.Converting -> {
                    binding.btnConvert.isEnabled = false
                    binding.btnConvert.text = getString(R.string.status_converting)
                }
                is ConversionUiState.Completed -> {
                    binding.btnConvert.isEnabled = true
                    binding.btnConvert.text = getString(R.string.convert_button)
                    Toast.makeText(this, getString(R.string.msg_convert_complete), Toast.LENGTH_SHORT).show()
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
                        prefs.edit().remove("input_uri").apply()
                        // Fallthrough to check legacy path or return false
                    }
                }

                // If permission is now okay (or was okay), check the file
                DocumentFile.fromTreeUri(this, uri)?.takeIf { it.exists() }?.let { docFile ->
                    viewModel.setInputDir(docFile)
                    logger.i("Successfully restored input folder: ${docFile.name}")
                    return true
                }

                logger.w("DocumentFile validation failed for URI: $uri")
                // Clear invalid URI
                prefs.edit().remove("input_uri").apply()

            } catch (e: Exception) {
                logger.w("Failed to restore saved input URI", e)
            }
        }

        if (savedInputPath != null) {
            logger.i("Restoring input folder from saved path (legacy)")
            
            File(savedInputPath).takeIf { it.exists() && it.isDirectory }?.let { file ->
                val docFile = DocumentFile.fromFile(file)
                viewModel.setInputDir(docFile)
                logger.i("Successfully restored input path: $savedInputPath")
                return true
            }
            
            logger.w("Legacy input path does not exist or is not a directory: $savedInputPath")
            // Clear invalid legacy path
            prefs.edit().remove("input_path").apply()
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
        if (requestCode == INPUT_FOLDER_REQUEST_CODE && viewModel.inputDir.value == null) {
             try {
                 initialUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2Fnetease%2Fcloudmusic%2FMusic")
             } catch (e: Exception) {
                 // Ignore
             }
        }
        
        openDocumentTreeLauncher.launch(initialUri)
    }
}