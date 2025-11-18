package uk.co.mrsheep.halive.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch
import uk.co.mrsheep.halive.R
import uk.co.mrsheep.halive.core.LogEntry

class DebugLogsBottomSheet : BottomSheetDialogFragment() {

    private lateinit var debugLogText: TextView
    private lateinit var debugLogScrollView: NestedScrollView

    companion object {
        fun newInstance(): DebugLogsBottomSheet {
            return DebugLogsBottomSheet()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_debug_logs_bottom_sheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Find views
        debugLogText = view.findViewById(R.id.debugLogText)
        debugLogScrollView = view.findViewById(R.id.debugLogScrollView)

        // Configure bottom sheet behavior for better scroll handling
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }

        // Get the MainActivity's viewModel
        val viewModel = (requireActivity() as MainActivity).provideViewModel()

        // Observe tool logs and update display
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.toolLogs.collect { logs ->
                updateLogs(logs)
            }
        }
    }

    /**
     * Format and display the logs in the bottom sheet
     */
    private fun updateLogs(logs: List<LogEntry>) {
        if (logs.isEmpty()) {
            debugLogText.text = "No logs yet..."
            return
        }

        // Format logs in chronological order (oldest first)
        val formattedLogs = logs.joinToString("\n\n") { log ->
            val statusIcon = if (log.success) "✓" else "✗"
            val statusColor = if (log.success) "SUCCESS" else "FAILED"

            """
            |[$statusIcon] ${log.timestamp} - $statusColor
            |Tool: ${log.toolName}
            |Params: ${log.parameters}
            |Result: ${log.result}
            """.trimMargin()
        }

        debugLogText.text = formattedLogs

        // Auto-scroll to bottom to show most recent log entry
        debugLogScrollView.post {
            debugLogScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }
}
