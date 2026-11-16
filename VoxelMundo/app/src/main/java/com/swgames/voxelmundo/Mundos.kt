package com.swgames.voxelmundo

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import androidx.documentfile.provider.DocumentFile
import java.io.File

// ===== Onde os mundos ficam guardados =====
// Cada mundo é uma pasta: mundos/<Nome>/mundo.json, jogador.json, entidades.json, regioes/*.json, icone.png
interface Disco {
    fun read(path: String): ByteArray?
    fun write(path: String, data: ByteArray): Boolean
    fun dirs(path: String): List<String>
    fun files(path: String): List<String>
    fun remove(path: String)
    fun nome(): String
}

// pasta comum (Documentos/VoxelMundo ou a pasta privada do app)
class DiscoArquivo(private val raiz: File, private val rotulo: String) : Disco {
    private fun f(p: String) = File(raiz, p)
    override fun read(path: String): ByteArray? = try { val x = f(path); if (x.isFile) x.readBytes() else null } catch (e: Exception) { null }
    override fun write(path: String, data: ByteArray): Boolean = try {
        val x = f(path); x.parentFile?.mkdirs(); val tmp = File(x.parentFile, x.name + ".tmp"); tmp.writeBytes(data)
        if (x.exists()) x.delete(); tmp.renameTo(x) || run { x.writeBytes(data); tmp.delete(); true }
    } catch (e: Exception) { false }
    override fun dirs(path: String): List<String> = f(path).listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
    override fun files(path: String): List<String> = f(path).listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList()
    override fun remove(path: String) { try { f(path).deleteRecursively() } catch (_: Exception) { } }
    override fun nome() = rotulo
}

// pasta que o jogador escolheu no seletor do Android (qualquer lugar, até cartão de memória)
class DiscoEscolhido(private val ctx: Context, private val arvore: Uri) : Disco {
    private val raiz: DocumentFile? get() = DocumentFile.fromTreeUri(ctx, arvore)
    private fun achar(path: String, criar: Boolean, pasta: Boolean): DocumentFile? {
        var d = raiz ?: return null; val partes = path.split('/').filter { it.isNotEmpty() }
        for ((i, p) in partes.withIndex()) { val ultimo = i == partes.size - 1; val x = d.findFile(p)
            d = when { x != null -> x; !criar -> return null; ultimo && !pasta -> d.createFile("application/octet-stream", p) ?: return null; else -> d.createDirectory(p) ?: return null } }
        return d
    }
    override fun read(path: String): ByteArray? = try { achar(path, false, false)?.let { ctx.contentResolver.openInputStream(it.uri)?.use { s -> s.readBytes() } } } catch (e: Exception) { null }
    override fun write(path: String, data: ByteArray): Boolean = try { val x = achar(path, true, false) ?: throw Exception(); ctx.contentResolver.openOutputStream(x.uri, "wt")?.use { it.write(data) }; true } catch (e: Exception) { false }
    override fun dirs(path: String): List<String> = achar(path, false, true)?.listFiles()?.filter { it.isDirectory }?.mapNotNull { it.name } ?: emptyList()
    override fun files(path: String): List<String> = achar(path, false, true)?.listFiles()?.filter { it.isFile }?.mapNotNull { it.name } ?: emptyList()
    override fun remove(path: String) { try { achar(path, false, true)?.delete() } catch (_: Exception) { } }
    override fun nome() = (raiz?.name ?: "Pasta escolhida")
}

class Mundos(private val act: Activity, private val pedirPasta: () -> Unit, private val avisarJs: (String) -> Unit) {
    private val prefs = act.getSharedPreferences("voxelmundo", Context.MODE_PRIVATE)
    var disco: Disco = abrir()

    private fun documentos() = DiscoArquivo(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "VoxelMundo"), "Documentos/VoxelMundo")
    private fun privada() = DiscoArquivo(File(act.getExternalFilesDir(null) ?: act.filesDir, "VoxelMundo"), "Pasta do app")
    private fun funciona(d: Disco) = d.write("mundos/.teste", "1".toByteArray()).also { if (it) d.remove("mundos/.teste") }

    private fun abrir(): Disco {
        val tipo = prefs.getString("local", "documentos")
        if (tipo == "escolhida") { val u = prefs.getString("arvore", null); if (u != null) { val d = DiscoEscolhido(act, Uri.parse(u)); if (funciona(d)) return d } }
        if (tipo != "privada") { val d = documentos(); if (funciona(d)) return d }
        return privada()
    }

    private fun copiar(de: Disco, para: Disco, path: String) {
        for (f in de.files(path)) de.read("$path/$f")?.let { para.write("$path/$f", it) }
        for (d in de.dirs(path)) copiar(de, para, "$path/$d")
    }
    private fun trocar(novo: Disco, tipo: String) { val velho = disco; if (velho.nome() != novo.nome()) try { copiar(velho, novo, "mundos") } catch (_: Exception) { }
        disco = novo; prefs.edit().putString("local", tipo).apply() }

    fun escolheuPasta(uri: Uri?) {
        if (uri == null) { avisarJs("cancelado"); return }
        try { act.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) { }
        prefs.edit().putString("arvore", uri.toString()).apply()
        val d = DiscoEscolhido(act, uri); if (!funciona(d)) { avisarJs("erro"); return }
        trocar(d, "escolhida"); avisarJs("ok")
    }

    // ---------- o que o jogo (JavaScript) enxerga como window.VMFS ----------
    @JavascriptInterface fun read(p: String): String? = disco.read(p)?.toString(Charsets.UTF_8)
    @JavascriptInterface fun write(p: String, t: String): Boolean = disco.write(p, t.toByteArray(Charsets.UTF_8))
    @JavascriptInterface fun readB64(p: String): String? = disco.read(p)?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
    @JavascriptInterface fun writeB64(p: String, b: String): Boolean = try { disco.write(p, Base64.decode(b, Base64.DEFAULT)) } catch (e: Exception) { false }
    @JavascriptInterface fun list(p: String): String = org.json.JSONArray(disco.dirs(p)).toString()
    @JavascriptInterface fun remove(p: String) { disco.remove(p) }
    @JavascriptInterface fun where(): String = disco.nome()
    @JavascriptInterface fun local(): String = prefs.getString("local", "documentos") ?: "documentos"
    @JavascriptInterface fun useDocuments(): Boolean { val d = documentos(); if (!funciona(d)) return false; trocar(d, "documentos"); return true }
    @JavascriptInterface fun useAppFolder(): Boolean { val d = privada(); if (!funciona(d)) return false; trocar(d, "privada"); return true }
    @JavascriptInterface fun pickFolder() { act.runOnUiThread { pedirPasta() } }
    // exportar: salva o .zip em Downloads/VoxelMundo
    @JavascriptInterface fun saveDownload(nome: String, b64: String): Boolean = try {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, nome); put(MediaStore.Downloads.MIME_TYPE, "application/zip"); put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VoxelMundo") }
            val uri = act.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw Exception()
            act.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }; true
        } else { val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VoxelMundo"); d.mkdirs(); File(d, nome).writeBytes(bytes); true }
    } catch (e: Exception) { false }
}
