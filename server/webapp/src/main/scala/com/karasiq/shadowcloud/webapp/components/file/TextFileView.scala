package com.karasiq.shadowcloud.webapp.components.file

import scala.scalajs.js.UndefOr

import rx.{Rx, Var}

import com.karasiq.bootstrap.Bootstrap.default._
import scalaTags.all._

import com.karasiq.common.memory.SizeUnit
import com.karasiq.highlightjs.HighlightJS
import com.karasiq.markedjs.{Marked, MarkedOptions, MarkedRenderer}
import com.karasiq.shadowcloud.model.File
import com.karasiq.shadowcloud.utils.Utils
import com.karasiq.shadowcloud.webapp.components.common.{AppComponents, AppIcons, TextEditor}
import com.karasiq.shadowcloud.webapp.context.{AppContext, FolderContext}
import com.karasiq.shadowcloud.webapp.context.AppContext.JsExecutionContext
import com.karasiq.shadowcloud.webapp.controllers.FileController
import com.karasiq.shadowcloud.webapp.utils.HtmlUtils

object TextFileView {
  private[this] val TextFormats = Set("txt", "ini", "csv", "log")

  private[this] val CodeFormats = Set(
    "sh", "clojure", "coffee", "c", "h", "cpp", "hpp", "cs", "d", "pas", "erl",
    "fs", "go", "groovy", "hs", "java", "js", "json", "lua", "lisp", "md", "m",
    "pl", "php", "py", "rb", "rust", "scala", "ss", "sql", "swift", "ts", "css",
    "xml", "html", "xhtml", "conf"
  )

  private[this] val RenderableFormats = Set(
    "html", "htm", "xhtml", "md"
  )

  def apply(file: File)(implicit context: AppContext, folderContext: FolderContext, fileController: FileController): TextFileView = {
    new TextFileView(file)
  }

  def canBeViewed(file: File): Boolean = {
    val sizeLimit = SizeUnit.MB * 10
    isTextFile(file) && file.checksum.size <= sizeLimit
  }

  def isTextFile(file: File): Boolean = {
    val extension = Utils.getFileExtensionLowerCase(file.path.name)
    TextFormats.contains(extension) || CodeFormats.contains(extension) || RenderableFormats.contains(extension)
  }

  def isCodeFile(file: File): Boolean = {
    val extension = Utils.getFileExtensionLowerCase(file.path.name)
    CodeFormats.contains(extension)
  }

  def isRenderableFile(file: File): Boolean = {
    val extension = Utils.getFileExtensionLowerCase(file.path.name)
    RenderableFormats.contains(extension)
  }
}

class TextFileView(_file: File)(implicit context: AppContext, folderContext: FolderContext,
                                fileController: FileController) extends BootstrapHtmlComponent {

  val editorOpened = Var(false)
  val fileRx = Var(_file)
  val contentRx = Var("")
  fileRx.trigger(fetchFileContent())

  def renderTag(md: ModifierT*): TagT = {
    val field = Rx {
      val file = fileRx()
      val content = contentRx()

      if (editorOpened()) {
        renderEditor(content)
      } else {
        if (TextFileView.isRenderableFile(file)) {
          renderHtml(Utils.getFileExtensionLowerCase(file.path.name), content)
        } else if (TextFileView.isCodeFile(file)) {
          renderCode(content)
        } else {
          renderPlain(content)
        }
      }
    }

    div(
      AppComponents.iconLink(context.locale.edit, AppIcons.editText, onclick := Callback.onClick { _ ⇒
        editorOpened() = !editorOpened.now
      }),
      div(field)
    )
  }

  private[this] def fetchFileContent(): Unit = {
    context.api.downloadFile(folderContext.regionId, fileRx.now.path, fileRx.now.id, folderContext.scope.now)
      .map(_.utf8String)
      .foreach(contentRx.update)
  }

  private[this] def renderEditor(content: String): TagT = {
    val editor = TextEditor { editor ⇒
      editor.submitting() = true
      val oldFile = fileRx.now
      val (_, future) = context.api.uploadFile(folderContext.regionId, oldFile.path, editor.value.now)
      future.onComplete(_ ⇒ editor.submitting() = false)
      future.foreach { newFile ⇒
        editorOpened() = false
        fileRx() = newFile
        fileController.updateFile(oldFile, newFile)
      }
    }
    editor.value() = content
    editor.renderTag()
  }

  private[this] def renderPlain(content: String): TagT = {
    Bootstrap.well(content, whiteSpace.`pre-wrap`, wordWrap.`break-word`)
  }

  private[this] def renderCode(content: String): TagT = {
    pre(raw(HighlightJS.highlightAuto(content).value))
  }

  private[this] def renderHtml(extension: String, content: String): TagT = extension match {
    case "htm" | "html" | "xhtml" ⇒
      div(HtmlUtils.extractContent(content))

    case "md" ⇒
      renderMarkdown(content)

    case _ ⇒
      renderPlain(content)
  }

  private[this] def renderMarkdown(content: String): TagT = {
    val options = MarkedOptions(
      highlight = { (source: String, lang: UndefOr[String], _: scalajs.js.Function) ⇒
        lang.fold(HighlightJS.highlightAuto(source))(HighlightJS.highlight(_, source)).value
      },
      renderer = MarkedRenderer(table = { (header: String, body: String) ⇒
        import scalatags.Text.all.{body ⇒ _, header ⇒ _, _}
        table(`class` := "table table-striped", thead(raw(header)), tbody(raw(body))).render
      }),
      breaks = true,
      smartypants = true,
      gfm = true
    )

    div(raw(Marked(content, options)))
  }
}

