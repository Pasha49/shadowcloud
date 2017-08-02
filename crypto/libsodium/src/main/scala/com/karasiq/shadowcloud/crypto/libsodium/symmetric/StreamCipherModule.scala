package com.karasiq.shadowcloud.crypto.libsodium.symmetric

import com.karasiq.shadowcloud.crypto.EncryptionMethod

private[libsodium] abstract class StreamCipherModule(val method: EncryptionMethod,
                                                     protected val keySize: Int,
                                                     protected val nonceSize: Int)
  extends SymmetricCipherModule with SymmetricCipherStreaming {

  protected var encrypt = true
  protected var key: Array[Byte] = Array.emptyByteArray
  protected var nonce: Array[Byte] = Array.emptyByteArray

  protected def process(inArray: Array[Byte], outArray: Array[Byte]): Unit

  protected def init(encrypt: Boolean, key: Array[Byte], nonce: Array[Byte]): Unit = {
    this.encrypt = encrypt
    this.key = key
    this.nonce = nonce
  }

  protected def process(data: Array[Byte]): Array[Byte] = {
    val outArray = new Array[Byte](data.length)
    process(data, outArray)
    outArray
  }
}
