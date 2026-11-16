package app.assi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shell's operations are the branches of one `when` in Native.kt. Kotlin takes the first
 * branch with a given name and says nothing about a second one — which then never runs. That is
 * how a new "icon.set" (the app's launcher icon) once hid behind the old one (a module's icon).
 */
class NativeNamesTest {
    @Test
    fun `no operation is named twice`() {
        // From the `when` on: the list of features above it names some operations as well.
        val source = File("src/main/java/app/assi/core/Native.kt").readText().substringAfter("fun call(")
        // Branches of the big `when`: a line that starts with names in quotes, at its indent
        // (a branch with many names runs over several lines, each ending with a comma).
        val branch = Regex("""^ {8}("[a-z][A-Za-z0-9.]*"(?:,\s*"[a-z][A-Za-z0-9.]*")*),?\s*(->|$)""", RegexOption.MULTILINE)
        val names = branch.findAll(source).flatMap { Regex("\"([^\"]+)\"").findAll(it.groupValues[1]).map { name -> name.groupValues[1] } }.toList()
        assertTrue("the branches of Native.call were not found: the test reads the wrong place", names.size > 100)
        val twice = names.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        assertEquals("operations named twice in Native.kt", emptySet<String>(), twice)
    }
}
