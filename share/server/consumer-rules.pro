# Ktor (CIO) referenziert java.lang.management für die Debugger-Erkennung (IntellijIdeaDebugDetector).
# Dieses Paket gibt es auf Android nicht. Ohne diese Regeln bricht R8 mit „Missing class“ ab. Die Regel
# unterdrückt nur die Warnung, sie behält keine Klasse. Der Laufzeitpfad der Erkennung ist auf Android nicht
# belegt (offener Punkt in ADR 0012).
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# Einstiegspunkte des Servers bleiben im Release erhalten (ADR 0012, ADR 0014). Ohne diese Regeln entfernt R8 den ganzen
# Serverpfad: In der Release-Variante gibt es keine Einrichtung (SpaceServerService.serverSetupProvider liefert null), und
# R8 schließt daraus, dass der Server nie startet. Der Release-Build prüft R8 aber mit dem Server (ADR 0012). Die Regeln
# halten die Klassen, nicht die Funktion. Ein Start bleibt ohne Einrichtung unmöglich.
# Abbau in Paket 3.3, sobald es einen echten Einstieg gibt (ADR 0014, Abschnitt „Offen“, docs/PLAN-M3.md).
-keep class io.github.labushuya.emberlight.share.server.KtorLocalServer { *; }
-keep class io.github.labushuya.emberlight.share.server.SpaceServerService$Companion { *; }
