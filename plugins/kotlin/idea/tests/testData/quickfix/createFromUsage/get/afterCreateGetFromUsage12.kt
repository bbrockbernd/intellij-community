// "Create method 'get' from usage" "true"
class Foo<T> {
    fun get(s: String, w: T): Any {
        throw UnsupportedOperationException("not implemented") //To change body of created methods use File | Settings | File Templates.
    }
}
fun <T> x (y: Foo<List<T>>, w: java.util.ArrayList<T>) {
    val z = y["", w]
}
