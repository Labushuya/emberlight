package io.github.labushuya.emberlight.share.space

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Eine Datenbank für den ganzen Prozess (Paket 3.3, ADR 0016): App und Server teilen dieselbe Instanz. */
@RunWith(RobolectricTestRunner::class)
class SharedSpacesDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    // Die geteilte Instanz bleibt offen: Sie lebt mit dem Prozess, Tests dürfen sie nicht schließen.
    @Test
    fun appAndServerGetTheSameInstanceFromAnyContext() {
        val fromApp = SpacesDatabase.shared(context)
        val fromServer = SpacesDatabase.shared(context.applicationContext)
        assertNotNull(fromApp)
        assertSame(fromApp, fromServer)
    }
}
