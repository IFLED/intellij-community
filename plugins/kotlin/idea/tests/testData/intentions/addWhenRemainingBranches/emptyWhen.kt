// WITH_RUNTIME
// ERROR: 'when' expression must be exhaustive, add necessary 'FOO', 'BAR', 'BAZ' branches or 'else' branch instead
// SKIP_ERRORS_AFTER

enum class Entry {
    FOO, BAR, BAZ
}

fun test(e: Entry) {
    when (e) {
        <caret>
    }
}