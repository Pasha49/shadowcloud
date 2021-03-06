package com.karasiq.shadowcloud.utils

import akka.util.ByteString

import com.karasiq.shadowcloud.config.{RegionConfig, StorageConfig}
import com.karasiq.shadowcloud.exceptions.SCExceptions
import com.karasiq.shadowcloud.model.{Chunk, Data}
import com.karasiq.shadowcloud.providers.CryptoModuleRegistry
import com.karasiq.shadowcloud.storage.utils.ChunkKeyMapper

private[shadowcloud] object ChunkUtils {
  def getPlainBytes(modules: CryptoModuleRegistry, chunk: Chunk): ByteString = {
    chunk.data match {
      case Data(plain, _) if plain.nonEmpty ⇒
        plain

      case Data(_, encrypted) if encrypted.nonEmpty ⇒
        val module = modules.encryptionModule(chunk.encryption.method)
        module.decrypt(encrypted, chunk.encryption)

      case _ ⇒
        throw SCExceptions.ChunkDataIsEmpty(chunk)
    }
  }

  def getEncryptedBytes(modules: CryptoModuleRegistry, chunk: Chunk): ByteString = {
    chunk.data match {
      case Data(_, encrypted) if encrypted.nonEmpty ⇒
        encrypted

      case Data(plain, _) if plain.nonEmpty ⇒
        val module = modules.encryptionModule(chunk.encryption.method)
        module.encrypt(plain, chunk.encryption)

      case _ ⇒
        throw SCExceptions.ChunkDataIsEmpty(chunk)
    }
  }

  def recoverChunkData(modules: CryptoModuleRegistry, chunk: Chunk): Chunk = {
    val withPlain = chunk.copy(data = chunk.data.copy(plain = getPlainBytes(modules, chunk)))
    val withEncrypted = withPlain.copy(data = withPlain.data.copy(encrypted = getEncryptedBytes(modules, withPlain)))
    withEncrypted
  }

  def chunkWithData(chunk: Chunk, data: ByteString): Chunk = {
    require(data.length == chunk.checksum.encSize, "Invalid size")
    chunk.copy(data = chunk.data.copy(encrypted = data))
  }

  def recoverChunkData(existingChunk: Chunk, newChunk: Chunk): Chunk = {
    // require(existingChunk.checksum == newChunk.checksum, "Not same chunks")
    if (Utils.isSameChunk(existingChunk, newChunk)) {
      newChunk
    } else {
      existingChunk.copy(data = newChunk.data.copy(encrypted = ByteString.empty))
    }
  }

  def getChunkKeyMapper(regionConfig: RegionConfig, storageConfig: StorageConfig) = {
    regionConfig.chunkKey
      .orElse(storageConfig.chunkKey)
      .getOrElse(ChunkKeyMapper.hash)
  }
}
