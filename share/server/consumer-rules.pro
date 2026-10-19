# Ktor (CIO) referenziert java.lang.management für die Debugger-Erkennung (IntellijIdeaDebugDetector).
# Dieses Paket gibt es auf Android nicht. Ohne diese Regeln bricht R8 mit „Missing class“ ab. Die Regel
# unterdrückt nur die Warnung, sie behält keine Klasse. Der Laufzeitpfad der Erkennung ist auf Android nicht
# belegt (offener Punkt in ADR 0012).
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# Keine -keep-Regeln für den Serverpfad (ADR 0014, Umsetzung 3.1a). R8 hält die Server-Pipeline über die Erreichbarkeit
# vom Debug-Einstieg aus: Der Empfänger liegt im Manifest des benchmark-Builds, dessen Ketten zur Einrichtung und zur
# Routing-Pipeline führen. Im Release gibt es bis Paket 3.3 keinen Einstieg, dort entfernt R8 den Serverpfad.
