// WITH_STDLIB
// FIX: Unwrap 'Deferred' return type (breaks use-sites!)

package kotlinx.coroutines

interface My {
    fun <caret>function(): Deferred<Int>
}
