package pos.output

import java.awt.print.PrinterJob
import java.util.concurrent.FutureTask
import javax.swing.SwingUtilities
import org.apache.pdfbox.Loader
import org.apache.pdfbox.printing.PDFPageable

/** Submits the actual designed PDF only after the user accepts the native printer dialog. */
object ReceiptDesignPrinter {
    fun print(pdf: ByteArray): Boolean = Loader.loadPDF(pdf).use { document ->
        val job = PrinterJob.getPrinterJob()
        job.jobName = "Retail POS receipt"
        job.setPageable(PDFPageable(document))
        val accepted = if (SwingUtilities.isEventDispatchThread()) job.printDialog() else {
            val request = FutureTask { job.printDialog() }
            SwingUtilities.invokeAndWait(request)
            request.get()
        }
        if (!accepted) return false
        job.print()
        true
    }
}
