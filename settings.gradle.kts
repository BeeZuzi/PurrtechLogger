rootProject.name = "PurrTechDetailLogger"

// PurrTechDisplayGUI is a separate local Gradle project (D:\Minecraft\pluginy\PurrTechDisplayGUI),
// not published to a repository. Including it as a composite build lets `compileOnly("eu.purrtech:
// PurrTechDisplayGUI:1.0")` in build.gradle.kts auto-substitute to its live project output, so
// Gradle always compiles against current DisplayGUI source - no more manually rebuilding it and
// copying the jar into lib/ by hand.
includeBuild("D:/Minecraft/pluginy/PurrTechDisplayGUI")
