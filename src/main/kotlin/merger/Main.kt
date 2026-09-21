package merger

import java.nio.file.Paths

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5221
    var data = "data"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> host = args[++i]
            "--port" -> port = args[++i].toInt()
            "--data" -> data = args[++i]
        }
        i++
    }
    val store = Store(Paths.get(data))
    store.load()
    val service = MergeService(store)
    WebApp(service, host, port).start()
    println("语义配置合并器已启动 → http://$host:$port")
    Thread.currentThread().join()
}
