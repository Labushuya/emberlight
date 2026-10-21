package io.github.labushuya.emberlight.feature.space.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.labushuya.emberlight.feature.space.upload.ContentHostUploadPort
import io.github.labushuya.emberlight.feature.space.upload.HostUploadPort

/** Bindung des Gastgeber-Uploads aus der App (Paket 3.5c). Tests ersetzen die Schnittstelle, nicht die Implementierung. */
@Module
@InstallIn(SingletonComponent::class)
abstract class HostUploadModule {
    @Binds
    abstract fun bindHostUploadPort(port: ContentHostUploadPort): HostUploadPort
}
