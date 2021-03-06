package com.karasiq.shadowcloud.webapp.context

import rx.Var

import com.karasiq.shadowcloud.model.{Path, RegionId}
import com.karasiq.shadowcloud.model.utils.IndexScope
import com.karasiq.shadowcloud.utils.Utils

trait FolderContext {
  def regionId: RegionId
  def scope: Var[IndexScope]
  def selected: Var[Path]
  def updates: Var[(Path, Long)]

  def update(path: Path): Unit = {
    updates() = (path, Utils.timestamp)
  }
}

object FolderContext {
  def apply(_regionId: RegionId): FolderContext = {
    new FolderContext {
      val regionId = _regionId
      val scope = Var(IndexScope.default)
      val selected = Var(Path.root)
      val updates = Var(Path.root, 0)
    }
  }
}
