/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.editor.impl.view;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.FoldRegion;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.SoftWrap;
import com.intellij.openapi.editor.ex.util.EditorUtil;
import com.intellij.openapi.editor.impl.EditorImpl;
import com.intellij.openapi.editor.impl.SoftWrapModelImpl;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class VisualLinesIterator {
  private final EditorImpl myEditor;
  private final Document myDocument;
  private final FoldRegion[] myFoldRegions;
  private final List<? extends SoftWrap> mySoftWraps;
  private final List<Inlay> myInlays;
  
  @NotNull
  private Location myLocation;
  private Location myNextLocation;
  
  public VisualLinesIterator(@NotNull EditorImpl editor, int startVisualLine) {
    myEditor = editor;
    SoftWrapModelImpl softWrapModel = myEditor.getSoftWrapModel();
    myDocument = myEditor.getDocument();
    FoldRegion[] regions = myEditor.getFoldingModel().fetchTopLevel();
    myFoldRegions = regions == null ? FoldRegion.EMPTY_ARRAY : regions;
    mySoftWraps = softWrapModel.getRegisteredSoftWraps();
    myInlays = EditorUtil.getVisibleLineExtendingElements(myEditor);
    myLocation = new Location(startVisualLine);
  }

  public boolean atEnd() {
    return myLocation.atEnd();
  }
  
  public void advance() {
    checkEnd();
    if (myNextLocation == null) {
      myLocation.advance();
    }
    else {
      myLocation = myNextLocation;
      myNextLocation = null;
    }
  }

  public int getVisualLine() {
    checkEnd();
    return myLocation.visualLine;
  }

  public int getVisualLineStartOffset() {
    checkEnd();
    return myLocation.offset;
  }
  
  public int getVisualLineEndOffset() {
    checkEnd();
    if (myNextLocation == null) {
      myNextLocation = myLocation.clone();
      myNextLocation.advance();
    }
    return myNextLocation.atEnd() ? myDocument.getTextLength() : 
           myNextLocation.softWrap == myLocation.softWrap ? myDocument.getLineEndOffset(myNextLocation.logicalLine - 2) : 
           myNextLocation.offset;
  }
  
  public int getStartLogicalLine() {
    checkEnd();
    return myLocation.logicalLine - 1;
  }  
  
  public int getStartOrPrevWrapIndex() {
    checkEnd();
    return myLocation.softWrap - 1;
  }
  
  public int getStartFoldingIndex() {
    checkEnd();
    return myLocation.foldRegion;
  }

  public int getY() {
    checkEnd();
    return myLocation.y;
  }

  public int getInlineInlaysHeight() {
    int height = myEditor.getLineHeight();
    int lineStartOffset = getVisualLineStartOffset();
    int lineEndOffset = getVisualLineEndOffset();
    for (int i = myLocation.inlay; i < myInlays.size(); i++) {
      Inlay inlay = myInlays.get(i);
      int offset = inlay.getOffset();
      if (offset > lineEndOffset || offset == lineEndOffset && lineEndOffset > lineStartOffset) break;
      if (inlay.getType() == Inlay.Type.INLINE) height = Math.max(height, inlay.getHeightInPixels());
    }
    return height;
  }

  private void checkEnd() {
    if (atEnd()) throw new IllegalStateException("Iteration finished");
  }

  private final class Location implements Cloneable {
    private int visualLine;       // current visual line
    private int offset;           // start offset of the current visual line
    private int logicalLine = 1;  // 1 + start logical line of the current visual line
    private int foldRegion;       // index of the first folding region on current or following visual lines
    private int softWrap;         // index of the first soft wrap after the start of current visual line
    private int inlay;            // index of the first visible block inlay displayed after current visual line
    private int y;                // y coordinate of visual line's top
    
    private Location(int startVisualLine) {
      if (startVisualLine < 0 || startVisualLine >= myEditor.getVisibleLineCount()) {
        offset = -1;
      }
      else if (startVisualLine > 0) {
        visualLine = startVisualLine;
        offset = myEditor.visualLineStartOffset(startVisualLine);
        logicalLine = myDocument.getLineNumber(offset) + 1;
        softWrap = myEditor.getSoftWrapModel().getSoftWrapIndex(offset) + 1;
        if (softWrap <= 0) {
          softWrap = -softWrap;
        }
        foldRegion = myEditor.getFoldingModel().getLastCollapsedRegionBefore(offset) + 1;
        y = myEditor.visibleLineToY(startVisualLine);
        while (inlay < myInlays.size() && myInlays.get(inlay).getOffset() < offset) inlay++;
      }
    }

    private void advance() {
      int nextWrapOffset = getNextSoftWrapOffset();
      offset = getNextVisualLineStartOffset(nextWrapOffset);
      if (offset == Integer.MAX_VALUE) {
        offset = -1;
      }
      else if (offset == nextWrapOffset) {
        softWrap++;
      }
      visualLine++;
      while (foldRegion < myFoldRegions.length && myFoldRegions[foldRegion].getStartOffset() < offset) foldRegion++;
      y += myEditor.getLineHeight();
      int inlineMaxHeight = myEditor.getLineHeight();
      while (inlay < myInlays.size() && myInlays.get(inlay).getOffset() < offset) {
        Inlay inlay1 = myInlays.get(this.inlay++);
        int height = inlay1.getHeightInPixels();
        if (inlay1.getType() == Inlay.Type.BLOCK) {
          y += height;
        }
        else if (height > inlineMaxHeight) {
          y += (height - inlineMaxHeight);
          inlineMaxHeight = height;
        }
      }
    }

    private int getNextSoftWrapOffset() {
      return softWrap < mySoftWraps.size() ? mySoftWraps.get(softWrap).getStart() : Integer.MAX_VALUE;
    }

    private int getNextVisualLineStartOffset(int nextWrapOffset) {
      while (logicalLine < myDocument.getLineCount()) {
        int lineStartOffset = myDocument.getLineStartOffset(logicalLine);
        if (lineStartOffset > nextWrapOffset) return nextWrapOffset;
        logicalLine++;
        if (!isCollapsed(lineStartOffset)) return lineStartOffset;
      }
      return nextWrapOffset;
    }

    private boolean isCollapsed(int offset) {
      while (foldRegion < myFoldRegions.length) {
        FoldRegion region = myFoldRegions[foldRegion];
        if (offset <= region.getStartOffset()) return false;
        if (offset <= region.getEndOffset()) return true;
        foldRegion++;
      }
      return false;
    }
    
    private boolean atEnd() {
      return offset == -1;
    }

    @Override
    protected Location clone() {
      try {
        return (Location)super.clone();
      }
      catch (CloneNotSupportedException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
