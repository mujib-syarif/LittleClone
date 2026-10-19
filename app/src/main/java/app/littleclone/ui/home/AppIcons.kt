package app.littleclone.ui.home

import android.content.Context
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.drawable.toBitmap
import app.littleclone.ui.theme.LocalTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Icons are decoded once at a small size and kept in a bounded cache (the phone has little RAM to spare). */
object AppIcons {
    private val cache = LruCache<String, ImageBitmap>(80)

    fun peek(packageName: String): ImageBitmap? = cache.get(packageName)

    suspend fun load(context: Context, packageName: String): ImageBitmap? {
        cache.get(packageName)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val bitmap = context.packageManager.getApplicationIcon(packageName).toBitmap(112, 112).asImageBitmap()
                cache.put(packageName, bitmap)
                bitmap
            } catch (e: Exception) {
                null
            }
        }
    }
}

@Composable
fun AppIcon(packageName: String, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val image by produceState(AppIcons.peek(packageName), packageName) {
        value = AppIcons.load(context, packageName)
    }
    val t = LocalTokens.current
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.225f))) {
        val img = image
        if (img != null) {
            Image(bitmap = img, contentDescription = null, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(t.glassTop))
        }
    }
}
