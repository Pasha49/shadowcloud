package com.karasiq.shadowcloud.webapp.components.folder

import com.karasiq.bootstrap.Bootstrap.default._
import scalaTags.all._

import rx.Rx

import com.karasiq.shadowcloud.index.Folder
import com.karasiq.shadowcloud.utils.MemorySize
import com.karasiq.shadowcloud.webapp.components.file.FileDownloadLink
import com.karasiq.shadowcloud.webapp.context.AppContext

object FolderFileList {
  def apply(regionId: String, folder: Rx[Folder])(implicit context: AppContext): FolderFileList = {
    new FolderFileList(regionId, folder)
  }
}

class FolderFileList(regionId: String, folder: Rx[Folder])(implicit context: AppContext) extends BootstrapHtmlComponent {
  def renderTag(md: ModifierT*): TagT = {
    val rows = folder.map(_.files.toSeq.sortBy(_.path.name).map { file ⇒
      TableRow(Seq(
        FileDownloadLink(regionId, file)(file.id.toString),
        file.path.name,
        MemorySize.toString(file.checksum.size),
      ))
    })
    val table = PagedTable(Rx(Seq(context.locale.fileId, context.locale.fileName, context.locale.fileSize)), rows)
    table.renderTag(md:_*)
  }
}