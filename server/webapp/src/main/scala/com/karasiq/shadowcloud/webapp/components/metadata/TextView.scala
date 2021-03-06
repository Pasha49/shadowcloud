package com.karasiq.shadowcloud.webapp.components.metadata

import com.karasiq.bootstrap.Bootstrap.default._
import scalaTags.all._

import com.karasiq.shadowcloud.metadata.Metadata
import com.karasiq.shadowcloud.webapp.context.AppContext
import com.karasiq.shadowcloud.webapp.utils.HtmlUtils

object TextView {
  def apply(text: Metadata.Text)(implicit context: AppContext): TextView = {
    new TextView(text)
  }
}

class TextView(text: Metadata.Text)(implicit context: AppContext) extends BootstrapHtmlComponent {
  def renderTag(md: ModifierT*): TagT = {
    if (text.format == HtmlUtils.HtmlMime) {
      Bootstrap.well(div(HtmlUtils.extractContent(text.data, text.format)), wordWrap.`break-word`, md)
    } else {
      Bootstrap.well(text.data, whiteSpace.`pre-wrap`, wordWrap.`break-word`, md)
    }
  }
}

