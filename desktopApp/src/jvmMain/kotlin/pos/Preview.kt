package pos

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import pos.data.*
import pos.ui.theme.PosTheme
import java.nio.file.Files

/** Isolated visual-review fixture. Never opens the normal database or a real payment service. */
fun main() {
    System.setProperty("pos.dataDir", Files.createTempDirectory("retail-pos-preview-").toString())
    application {
        val repo = remember { PosRepository(":memory:") }
        val tenant = remember {
            repo.createTenant("Practice shop · Sample data").also { t ->
                val malt=repo.addProduct(t.id,"Malt drink · 330 ml","bottle",50000,240.0)
                val rice=repo.addProduct(t.id,"Premium rice · 5 kg","bag",1250000,32.0)
                repo.addProduct(t.id,"Vegetable oil · 1 litre","bottle",380000,48.0)
                repo.addProduct(t.id,"Laundry soap · 200 g","piece",45000,96.0)
                repo.addProduct(t.id,"Tomato paste · 70 g","sachet",25000,120.0)
                repo.addCustomer(t.id,"Amina · Sample customer")
                DraftStore(t.id).save(SaleDraft("${malt.id}:3;${rice.id}:1",java.util.UUID.randomUUID().toString()))
            }
        }
        Window(onCloseRequest = ::exitApplication, title="Retail POS · Isolated preview",state=rememberWindowState(width=1440.dp,height=900.dp)) { PosTheme { App(repo,tenant) } }
    }
}
