# Ktor (CIO) referenziert java.lang.management für die Debugger-Erkennung (IntellijIdeaDebugDetector).
# Dieses Paket gibt es auf Android nicht. Ohne diese Regeln bricht R8 mit „Missing class“ ab. Die Regel
# unterdrückt nur die Warnung, sie behält keine Klasse. Der Laufzeitpfad der Erkennung ist auf Android nicht
# belegt (offener Punkt in ADR 0012).
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# Keine -keep-Regeln für den Serverpfad (ADR 0014, ADR 0017). R8 hält die Server-Pipeline über die Erreichbarkeit: Seit
# Paket 3.3 startet der Gastgeber den Space aus der Oberfläche des Release-Builds, der Dienst steht im Manifest von
# share:server. Die Prüfung checkReleaseServerPipeline liest die Mapping-Datei und bricht ab, falls R8 Teile der
# Pipeline entfernt hat.

# Der Serverpfad bleibt im Release erhalten, die Prüfung checkReleaseServerPipeline verlangt die Klasse (ADR 0018).
-keep class io.github.labushuya.emberlight.share.server.KtorLocalServer {
    <init>(...);
    public *;
}
