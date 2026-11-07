package shoplens

import java.io.File

/**
 * Settings come from environment variables, falling back to a `.env` file in
 * the working directory (KEY=value per line; # comments). `.env` is gitignored.
 */
object Config {
    private val dotenv: Map<String, String> by lazy {
        val f = File(".env")
        if (!f.isFile) return@lazy emptyMap()
        f.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
            .associate { line ->
                val (k, v) = line.removePrefix("export ").split("=", limit = 2)
                k.trim() to v.trim().removeSurrounding("\"").removeSurrounding("'")
            }
    }

    fun get(key: String): String? = System.getenv(key)?.takeIf { it.isNotBlank() } ?: dotenv[key]?.takeIf { it.isNotBlank() }
}
