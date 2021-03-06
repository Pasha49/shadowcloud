package com.karasiq.shadowcloud.crypto.bouncycastle.asymmetric

import scala.language.postfixOps

import akka.util.ByteString
import org.bouncycastle.crypto.AsymmetricBlockCipher

import com.karasiq.shadowcloud.crypto.{EncryptionModuleStreamer, OnlyStreamEncryptionModule}
import com.karasiq.shadowcloud.utils.ByteStringUnsafe

private[bouncycastle] trait BCAsymmetricCipherModule extends OnlyStreamEncryptionModule with BCAsymmetricCipherKeys

private[bouncycastle] trait BCAsymmetricBlockCipherStreamer extends EncryptionModuleStreamer {
  protected def cipher: AsymmetricBlockCipher

  def process(data: ByteString): ByteString = {
    requireInitialized()
    val blockSize = cipher.getInputBlockSize
    if (data.length <= blockSize) {
      // Single block
      ByteString.fromArrayUnsafe(cipher.processBlock(ByteStringUnsafe.getArray(data), 0, data.length))
    } else {
      // Split to blocks
      data.grouped(blockSize)
        .map(block ⇒ ByteString.fromArrayUnsafe(cipher.processBlock(block.toArray, 0, block.length)))
        .fold(ByteString.empty)(_ ++ _)
    }
  }

  def finish(): ByteString = {
    ByteString.empty
  }

  private[this] def requireInitialized(): Unit = {
    require(cipher.ne(null), "Not initialized")
  }
}