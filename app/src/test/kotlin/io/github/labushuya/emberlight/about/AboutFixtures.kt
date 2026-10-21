package io.github.labushuya.emberlight.about

/**
 * Feste Eingaben für die Tests des Bildschirms „Über & Lizenzen“. Die Lizenztexte sind kurz, damit Goldens stabil und
 * klein bleiben. Die echten Texte prüft [AboutLicensesTest] über die Assets.
 */
internal object AboutFixtures {
    const val VERSION = "1.2.3-test"

    /** Lizenzliste im Format des Gradle-Plugins: Bibliotheken mit Lizenz-IDs, dazu die Volltexte je ID. */
    val LIBRARY_JSON =
        """
        {
          "libraries": [
            {
              "uniqueId": "com.example:zeta",
              "artifactVersion": "2.0.0",
              "name": "Zeta",
              "description": "",
              "website": "",
              "developers": [],
              "licenses": ["Apache-2.0"],
              "funding": []
            },
            {
              "uniqueId": "com.example:alpha",
              "artifactVersion": "1.0.0",
              "name": "Alpha",
              "description": "",
              "website": "",
              "developers": [],
              "licenses": ["MIT"],
              "funding": []
            },
            {
              "uniqueId": "com.example:bare",
              "artifactVersion": "0.1.0",
              "name": "Bare",
              "description": "",
              "website": "",
              "developers": [],
              "licenses": [],
              "funding": []
            }
          ],
          "licenses": {
            "Apache-2.0": {
              "name": "Apache License 2.0",
              "url": "https://spdx.org/licenses/Apache-2.0.html",
              "content": "Apache test text"
            },
            "MIT": {
              "name": "MIT License",
              "url": "https://spdx.org/licenses/MIT.html",
              "content": "MIT test text"
            }
          }
        }
        """.trimIndent()

    val LIBRARIES =
        listOf(
            LibraryEntry(
                id = "com.example:alpha",
                name = "Alpha",
                version = "1.0.0",
                licenses = listOf(LicenseEntry(name = "MIT License", text = "MIT test text")),
            ),
            LibraryEntry(
                id = "com.example:bare",
                name = "Bare",
                version = "0.1.0",
                licenses = emptyList(),
            ),
            LibraryEntry(
                id = "com.example:zeta",
                name = "Zeta",
                version = "2.0.0",
                licenses = listOf(LicenseEntry(name = "Apache License 2.0", text = "Apache test text")),
            ),
        )

    val CONTENT =
        AboutContent(
            versionName = VERSION,
            libraries = LibraryList.Loaded(LIBRARIES),
            fonts =
                listOf(
                    LicenseDocument(title = "Fraunces", text = "Fraunces test text"),
                    LicenseDocument(title = "Inter", text = "Inter test text"),
                ),
            gpl = LicenseDocument(title = "GPL-3.0-or-later", text = "GNU GENERAL PUBLIC LICENSE test text"),
        )
}
