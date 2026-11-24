package app.toque.companion.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.DecodeHintType
import com.google.zxing.FormatException
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Lecture d'un code QR dans une image de luminance (le plan Y d'une image YUV de l'appareil
 * photo), avec ZXing. Pas d'Android ici : testé sur la JVM avec des images fabriquées.
 *
 * Seul le carré central de l'image est lu ([crop] = part du petit côté) : c'est la zone que
 * l'aperçu montre dans le cadre de l'écran d'appairage. L'orientation n'importe pas (ZXing trouve
 * le code tourné). Deux passes : seuil local (HybridBinarizer, bon pour un éclairage inégal), puis
 * seuil global si la première échoue (grands modules bruités, que le seuil local découpe).
 * Pas sûr entre fils : une instance par fil de décodage.
 */
class QrDecoder(private val crop: Float = 1f) {
    private val reader = QRCodeReader()
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    /**
     * Lit [luma] : [width] × [height] octets de luminance, une ligne tous les [rowStride] octets
     * (le `rowStride` du plan Y, au moins [width]). Renvoie le texte du code, ou null.
     */
    fun decode(luma: ByteArray, width: Int, height: Int, rowStride: Int = width): String? {
        require(rowStride >= width && width > 0 && height > 0) { "image invalide : $width × $height, pas de ligne $rowStride" }
        val side = (minOf(width, height) * crop.coerceIn(0.1f, 1f)).toInt()
        val left = (width - side) / 2
        val top = (height - side) / 2
        // dataWidth = rowStride : les octets de bourrage en fin de ligne restent hors du carré lu.
        val source = PlanarYUVLuminanceSource(luma, rowStride, height, left, top, side, side, false)
        return read(BinaryBitmap(HybridBinarizer(source))) ?: read(BinaryBitmap(GlobalHistogramBinarizer(source)))
    }

    private fun read(bitmap: BinaryBitmap): String? =
        try {
            reader.decode(bitmap, hints).text
        } catch (e: NotFoundException) {
            null
        } catch (e: ChecksumException) {
            null
        } catch (e: FormatException) {
            null
        } finally {
            reader.reset()
        }
}
