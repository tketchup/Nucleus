rootProject.name = "Nucleus"

// Add-on für Pl3xMap: Pl3xMap veröffentlicht für 26.x keine Maven-API, deshalb wird gegen die Plugin-Jar
// kompiliert. Liegt sie nicht in addons/pl3xmap/libs/, wird das Add-on einfach nicht mitgebaut.
if (file("addons/pl3xmap/libs/Pl3xMap.jar").isFile) {
    include("addons:pl3xmap")
} else {
    println("Hinweis: addons/pl3xmap/libs/Pl3xMap.jar fehlt, das Pl3xMap-Add-on wird nicht gebaut.")
}
