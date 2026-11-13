package com.health.app.ui.modules.photos

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.Units
import com.health.app.net.deletePhoto
import com.health.app.net.updatePhoto
import com.health.app.net.uploadPhoto
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.Loading
import com.health.app.ui.components.appContainer

/**
 * Progress photos' pages, by [page]: "" (albums and the grid), "photo" ([id]), "compare"
 * (an [album]'s photos, from [id] onwards), "add" ([id] "camera" or "gallery"). Every one is kept
 * out of screenshots and behind the photo lock when it's on.
 */
@Composable
fun PhotosPage(page: String, id: String?, album: String?, onBack: () -> Unit, open: (String) -> Unit) {
    PhotoGate(onBack) {
        val c = appContainer()
        val vm = viewModel { PhotosViewModel(c) }
        val state by vm.state.collectAsState()
        val profile by c.profile.collectAsState()
        val viewProfile by c.viewProfile.collectAsState()
        val viewing by c.viewing.collectAsState()
        val units = (if (viewing != null) viewProfile?.units else profile?.units) ?: Units.METRIC
        val zone = c.zone()
        when (page) {
            "photo" -> {
                val photo = state.photos.firstOrNull { it.id == id }
                if (photo == null) {
                    Column { BackBar(onBack); Loading() }
                } else {
                    PhotoDetail(
                        photo, state.albums, units, zone, NetworkPhoto, onBack,
                        onSave = { p -> vm.act("Saved") { it.updatePhoto(p) } },
                        onDelete = { pid -> vm.act("Photo deleted") { it.deletePhoto(pid) }; onBack() },
                        onCompare = { open("photos/compare?album=${photo.album}&id=${photo.id}") },
                    )
                }
            }
            "compare" -> {
                val list = state.photos.filter { album == null || it.album.equals(album, ignoreCase = true) }
                if (state.loading && list.isEmpty()) {
                    Column { BackBar(onBack); Loading() }
                } else {
                    val chosen = list.firstOrNull { it.id == id }
                    PhotoCompare(
                        list.filter { chosen == null || it.pose == chosen.pose }.ifEmpty { list }, units, zone, NetworkPhoto,
                        initialBefore = null, initialAfter = chosen?.id, onBack = onBack,
                    )
                }
            }
            "add" -> PhotoAddPage(id ?: "camera", album, state.photos, state.albums, NetworkPhoto, onBack) { picked, details ->
                vm.act("Photo saved") { it.uploadPhoto(picked.bytes, picked.type, details) }
                onBack()
            }
            else -> {
                var shownAlbum by rememberSaveable { mutableStateOf(album) }
                PhotosHome(state, shownAlbum, { shownAlbum = it }, NetworkPhoto, PhotosActions(onBack, open) { vm.load() })
            }
        }
    }
}
