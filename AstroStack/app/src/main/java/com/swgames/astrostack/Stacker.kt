package com.swgames.astrostack

import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// Este arquivo é Kotlin puro (sem Android), pra poder ser testado no computador.

/** Uma foto convertida em cores (0..255) + brilho (lum). */
class Frame(
    val w: Int,
    val h: Int,
    val r: FloatArray,
    val g: FloatArray,
    val b: FloatArray,
    val lum: FloatArray,
) {
    /** Fundo do céu (mediana) e ruído (sigma) estimados por amostragem. */
    fun bgStats(): Pair<Float, Float> {
        val step = max(1, (w * h) / 60000)
        val n = (w * h) / step
        val s = FloatArray(n)
        for (k in 0 until n) s[k] = lum[k * step]
        s.sort()
        val med = s[n / 2]
        for (k in 0 until n) s[k] = abs(s[k] - med)
        s.sort()
        val sigma = max(1.4826f * s[n / 2], 0.5f)
        return Pair(med, sigma)
    }

    /** Pixels quentes: pontinhos isolados muito mais fortes que os 4 vizinhos. */
    fun removeHotPixels(sigma: Float) {
        val thr = 4f * sigma + 15f
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val v = lum[i]
                val m = max(max(lum[i - 1], lum[i + 1]), max(lum[i - w], lum[i + w]))
                if (v > m + thr) {
                    lum[i] = (lum[i - 1] + lum[i + 1] + lum[i - w] + lum[i + w]) * 0.25f
                    r[i] = (r[i - 1] + r[i + 1] + r[i - w] + r[i + w]) * 0.25f
                    g[i] = (g[i - 1] + g[i + 1] + g[i - w] + g[i + w]) * 0.25f
                    b[i] = (b[i - 1] + b[i + 1] + b[i - w] + b[i + w]) * 0.25f
                }
            }
        }
    }
}

data class Star(val x: Float, val y: Float, val flux: Float)

/** Transformação (rotação + deslocamento) que leva a foto nova até a foto de referência. */
data class Transform(val c: Float, val s: Float, val tx: Float, val ty: Float) {
    fun mapX(x: Float, y: Float) = c * x - s * y + tx
    fun mapY(x: Float, y: Float) = s * x + c * y + ty

    companion object {
        val IDENTITY = Transform(1f, 0f, 0f, 0f)
    }
}

object StarDetector {
    fun detect(f: Frame, maxStars: Int): List<Star> {
        val (med, sig) = f.bgStats()
        val thr = med + max(5f * sig, 8f)
        val low = med + 2.5f * sig
        val w = f.w
        val h = f.h
        val l = f.lum
        val border = 10
        val found = ArrayList<Star>()
        for (y in border until h - border) {
            val row = y * w
            for (x in border until w - border) {
                val i = row + x
                val v = l[i]
                if (v <= thr) continue
                var isMax = true
                var count = 0
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        if (dx == 0 && dy == 0) continue
                        val u = l[i + dy * w + dx]
                        if (u > v || (u == v && (dy < 0 || (dy == 0 && dx < 0)))) isMax = false
                        if (u > low) count++
                    }
                }
                // count >= 3 descarta pixel quente solitário
                if (!isMax || count < 3) continue
                var sx = 0f
                var sy = 0f
                var sw = 0f
                for (dy in -3..3) {
                    for (dx in -3..3) {
                        val wt = l[i + dy * w + dx] - med
                        if (wt > 0f) {
                            sx += wt * dx
                            sy += wt * dy
                            sw += wt
                        }
                    }
                }
                if (sw > 0f) found.add(Star(x + sx / sw, y + sy / sw, sw))
            }
        }
        found.sortByDescending { it.flux }
        return if (found.size > maxStars) found.subList(0, maxStars).toList() else found
    }
}

object Aligner {
    /** Retorna a transformação cur -> ref, ou null se não deu pra alinhar. */
    fun align(ref: List<Star>, cur: List<Star>, prior: Transform, width: Int): Transform? {
        if (ref.size < 5 || cur.size < 5) return null
        val r = ref.take(50)
        val cAll = cur.take(80)
        val c = cAll.take(50)

        // 1) Votação: qual deslocamento junta mais pares de estrelas?
        val bin = 3f
        val maxShift = width * 0.25f
        val votes = HashMap<Long, Int>()
        val px = FloatArray(c.size) { prior.mapX(c[it].x, c[it].y) }
        val py = FloatArray(c.size) { prior.mapY(c[it].x, c[it].y) }
        for (rs in r) {
            for (j in c.indices) {
                val dx = rs.x - px[j]
                val dy = rs.y - py[j]
                if (abs(dx) > maxShift || abs(dy) > maxShift) continue
                val key = key((dx / bin).roundToInt(), (dy / bin).roundToInt())
                votes[key] = (votes[key] ?: 0) + 1
            }
        }
        if (votes.isEmpty()) return null
        var bestKey = 0L
        var bestScore = -1
        for (k in votes.keys) {
            val kx = (k shr 32).toInt()
            val ky = k.toInt()
            var score = 0
            for (a in -1..1) for (b in -1..1) score += votes[key(kx + a, ky + b)] ?: 0
            if (score > bestScore) {
                bestScore = score
                bestKey = k
            }
        }
        val offX = (bestKey shr 32).toInt() * bin
        val offY = bestKey.toInt() * bin

        // 2) Pares perto do deslocamento vencedor
        val coarse = Transform(prior.c, prior.s, prior.tx + offX, prior.ty + offY)
        var t = fit(match(r, c, coarse, bin * 2f)) ?: return null

        // 3) Refinamento com mais estrelas e tolerância menor
        val fine = match(ref.take(80), cAll, t, 2.5f)
        t = fit(fine) ?: t

        // Sanidade: rotação pequena entre fotos
        if (abs(t.s) > 0.2f) return null
        return t
    }

    private fun key(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)

    private fun match(ref: List<Star>, cur: List<Star>, t: Transform, tol: Float): List<Pair<Star, Star>> {
        val pairs = ArrayList<Pair<Star, Star>>()
        val tol2 = tol * tol
        for (cs in cur) {
            val mx = t.mapX(cs.x, cs.y)
            val my = t.mapY(cs.x, cs.y)
            var best: Star? = null
            var bestD = tol2
            for (rs in ref) {
                val dx = rs.x - mx
                val dy = rs.y - my
                val d = dx * dx + dy * dy
                if (d < bestD) {
                    bestD = d
                    best = rs
                }
            }
            if (best != null) pairs.add(Pair(cs, best))
        }
        return pairs
    }

    /** Ajuste por mínimos quadrados (Kabsch 2D): rotação + deslocamento. */
    private fun fit(pairs: List<Pair<Star, Star>>): Transform? {
        if (pairs.size < 4) return null
        var ax = 0.0
        var ay = 0.0
        var bx = 0.0
        var by = 0.0
        for ((a, b) in pairs) {
            ax += a.x; ay += a.y; bx += b.x; by += b.y
        }
        val n = pairs.size.toDouble()
        ax /= n; ay /= n; bx /= n; by /= n
        var sxx = 0.0
        var sxy = 0.0
        for ((a, b) in pairs) {
            val x1 = a.x - ax
            val y1 = a.y - ay
            val x2 = b.x - bx
            val y2 = b.y - by
            sxx += x1 * x2 + y1 * y2
            sxy += x1 * y2 - y1 * x2
        }
        val th = atan2(sxy, sxx)
        val c = cos(th)
        val s = sin(th)
        val tx = bx - (c * ax - s * ay)
        val ty = by - (s * ax + c * ay)
        return Transform(c.toFloat(), s.toFloat(), tx.toFloat(), ty.toFloat())
    }
}

/** A pilha: soma das fotos alinhadas. */
class Stacker(val w: Int, val h: Int) {
    private val sr = FloatArray(w * h)
    private val sg = FloatArray(w * h)
    private val sb = FloatArray(w * h)
    private val cnt = ShortArray(w * h)
    private var refStars: List<Star>? = null
    private var prior = Transform.IDENTITY
    var stacked = 0
        private set
    var lastStarCount = 0
        private set

    /** Adiciona uma foto. Retorna false se ela foi rejeitada (não alinhou). */
    fun add(f: Frame): Boolean {
        if (f.w != w || f.h != h) return false
        val (_, sigma) = f.bgStats()
        f.removeHotPixels(sigma)
        val stars = StarDetector.detect(f, 80)
        lastStarCount = stars.size
        val ref = refStars
        val t: Transform
        if (ref == null) {
            if (stars.size < 6) return false
            refStars = stars
            t = Transform.IDENTITY
        } else {
            t = Aligner.align(ref, stars, prior, w) ?: return false
            prior = t
        }
        accumulate(f, t)
        stacked++
        return true
    }

    private fun accumulate(f: Frame, t: Transform) {
        val c = t.c
        val s = t.s
        val maxX = (w - 1).toFloat()
        val maxY = (h - 1).toFloat()
        for (y in 0 until h) {
            val dy = y - t.ty
            val dx0 = -t.tx
            // inversa: fonte = R^T * (destino - t)
            var xs = c * dx0 + s * dy
            var ys = -s * dx0 + c * dy
            val row = y * w
            for (x in 0 until w) {
                if (xs >= 0f && ys >= 0f && xs < maxX && ys < maxY) {
                    val x0 = xs.toInt()
                    val y0 = ys.toInt()
                    val fx = xs - x0
                    val fy = ys - y0
                    val w00 = (1f - fx) * (1f - fy)
                    val w10 = fx * (1f - fy)
                    val w01 = (1f - fx) * fy
                    val w11 = fx * fy
                    val i = y0 * w + x0
                    val o = row + x
                    sr[o] += f.r[i] * w00 + f.r[i + 1] * w10 + f.r[i + w] * w01 + f.r[i + w + 1] * w11
                    sg[o] += f.g[i] * w00 + f.g[i + 1] * w10 + f.g[i + w] * w01 + f.g[i + w + 1] * w11
                    sb[o] += f.b[i] * w00 + f.b[i + 1] * w10 + f.b[i + w] * w01 + f.b[i + w + 1] * w11
                    cnt[o] = (cnt[o] + 1).toShort()
                }
                xs += c
                ys -= s
            }
        }
    }

    /**
     * Gera a imagem final esticada (cores ARGB).
     * @param scale 1 = resolução cheia, 2 = metade (pra mostrar na tela)
     * @param strength 0..100, força do realce
     * @param removeGradient tira o brilho da poluição luminosa
     */
    fun render(scale: Int, strength: Int, removeGradient: Boolean): RenderedImage? {
        if (stacked == 0) return null
        val dw = w / scale
        val dh = h / scale
        val n = dw * dh
        val rr = FloatArray(n)
        val gg = FloatArray(n)
        val bb = FloatArray(n)
        for (y in 0 until dh) {
            for (x in 0 until dw) {
                var r = 0f
                var g = 0f
                var b = 0f
                var k = 0
                for (yy in 0 until scale) {
                    val row = (y * scale + yy) * w
                    for (xx in 0 until scale) {
                        val i = row + x * scale + xx
                        val c = cnt[i].toInt()
                        if (c > 0) {
                            r += sr[i] / c; g += sg[i] / c; b += sb[i] / c; k++
                        }
                    }
                }
                val o = y * dw + x
                if (k > 0) {
                    rr[o] = r / k; gg[o] = g / k; bb[o] = b / k
                }
            }
        }
        if (removeGradient) {
            subtractGradient(rr, dw, dh)
            subtractGradient(gg, dw, dh)
            subtractGradient(bb, dw, dh)
        }
        // Ponto preto por canal (mediana) = céu neutro, sem cor esverdeada/azulada
        val br = percentile(rr, 0.5f)
        val bg = percentile(gg, 0.5f)
        val bbk = percentile(bb, 0.5f)
        val lumS = FloatArray(n)
        for (i in 0 until n) lumS[i] = ((rr[i] - br) + (gg[i] - bg) + (bb[i] - bbk)) / 3f
        val white = max(percentile(lumS, 0.9995f), 1f)
        val k = 1f + strength * 0.6f
        val norm = asinh(k)
        val pedestal = 0.025f
        val px = IntArray(n)
        for (i in 0 until n) {
            val r = stretch((rr[i] - br) / white + pedestal, k, norm)
            val g = stretch((gg[i] - bg) / white + pedestal, k, norm)
            val b = stretch((bb[i] - bbk) / white + pedestal, k, norm)
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return RenderedImage(dw, dh, px)
    }

    private fun stretch(v: Float, k: Float, norm: Float): Int {
        val x = if (v <= 0f) 0f else asinh(k * v) / norm
        return (min(1f, x) * 255f).toInt()
    }

    private fun percentile(a: FloatArray, p: Float): Float {
        val step = max(1, a.size / 50000)
        val n = a.size / step
        val s = FloatArray(n) { a[it * step] }
        s.sort()
        return s[min(n - 1, (p * n).toInt())]
    }

    /** Estima o fundo numa grade (percentil baixo, ignora estrelas) e subtrai. */
    private fun subtractGradient(a: FloatArray, dw: Int, dh: Int) {
        val gx = 8
        val gy = max(4, (gx * dh.toFloat() / dw).roundToInt())
        val cw = dw.toFloat() / gx
        val ch = dh.toFloat() / gy
        val grid = FloatArray(gx * gy)
        for (cy in 0 until gy) {
            for (cx in 0 until gx) {
                val x0 = (cx * cw).toInt()
                val x1 = min(dw, ((cx + 1) * cw).toInt())
                val y0 = (cy * ch).toInt()
                val y1 = min(dh, ((cy + 1) * ch).toInt())
                val vals = ArrayList<Float>()
                var y = y0
                while (y < y1) {
                    var x = x0
                    while (x < x1) {
                        vals.add(a[y * dw + x]); x += 3
                    }
                    y += 3
                }
                vals.sort()
                grid[cy * gx + cx] = if (vals.isEmpty()) 0f else vals[(vals.size * 0.3f).toInt()]
            }
        }
        val base = grid.sorted()[0]
        for (y in 0 until dh) {
            val fy = ((y + 0.5f) / ch - 0.5f).coerceIn(0f, (gy - 1).toFloat())
            val y0 = min(fy.toInt(), gy - 1)
            val y1 = min(y0 + 1, gy - 1)
            val ty = fy - y0
            for (x in 0 until dw) {
                val fx = ((x + 0.5f) / cw - 0.5f).coerceIn(0f, (gx - 1).toFloat())
                val x0 = min(fx.toInt(), gx - 1)
                val x1 = min(x0 + 1, gx - 1)
                val tx = fx - x0
                val top = grid[y0 * gx + x0] * (1 - tx) + grid[y0 * gx + x1] * tx
                val bot = grid[y1 * gx + x0] * (1 - tx) + grid[y1 * gx + x1] * tx
                a[y * dw + x] -= (top * (1 - ty) + bot * ty) - base
            }
        }
    }
}

class RenderedImage(val w: Int, val h: Int, val pixels: IntArray)
