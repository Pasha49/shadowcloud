package com.karasiq.shadowcloud.crypto.bouncycastle.sign

import scala.language.postfixOps

import org.bouncycastle.crypto.DSA
import org.bouncycastle.crypto.signers.ECDSASigner

import com.karasiq.shadowcloud.crypto.{HashingMethod, SignMethod, SignModule, SignModuleStreamer}

private[bouncycastle] object ECDSASignModule {
  def apply(method: SignMethod = SignMethod("ECDSA", HashingMethod.default)): ECDSASignModule = {
    new ECDSASignModule(method)
  }
}

private[bouncycastle] final class ECDSASignModule(val method: SignMethod) extends BCSignModule with BCECKeys {
  def createStreamer(): SignModuleStreamer = {
    new ECDSASignerStreamer
  }

  protected class ECDSASignerStreamer extends BCDSAStreamer {
    protected val dsaSigner: DSA = new ECDSASigner()
    def module: SignModule = ECDSASignModule.this
  }
}
