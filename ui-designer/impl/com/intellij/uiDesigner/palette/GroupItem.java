package com.intellij.uiDesigner.palette;

import com.intellij.ide.palette.PaletteGroup;
import com.intellij.ide.palette.PaletteItem;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.uiDesigner.SimpleTransferable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.datatransfer.Transferable;
import java.util.ArrayList;

/**
 * @author Vladimir Kondratyev
 */
public final class GroupItem implements Cloneable, PaletteGroup {
  private static final Logger LOG = Logger.getInstance("#com.intellij.uiDesigner.palette.GroupItem");

  @NotNull private String myName;
  @NotNull private final ArrayList<ComponentItem> myItems;

  public GroupItem(@NotNull final String name) {
    setName(name);
    myItems = new ArrayList<ComponentItem>();
  }

  /**
   * @return deep copy of the {@link GroupItem} with copied items.
   */
  public GroupItem clone(){
    final GroupItem result = new GroupItem(myName);

    for(ComponentItem myItem : myItems) {
      result.addItem(myItem.clone());
    }

    return result;
  }

  @NotNull public String getName() {
    return myName;
  }

  public String getTabName() {
    return "Swing";
  }

  public void setName(@NotNull final String name){
    myName = name;
  }

  /**
   * @return read-only list of items that belong to the group.
   */
  @NotNull public ComponentItem[] getItems() {
    return myItems.toArray(new ComponentItem[myItems.size()]);
  }

  /** Adds specified {@link ComponentItem} to the group.*/
  public void addItem(@NotNull final ComponentItem item){
    LOG.assertTrue(!myItems.contains(item));

    myItems.add(item);
  }

  /** Replaces specified item with the new one. */
  public void replaceItem(@NotNull final ComponentItem itemToBeReplaced, @NotNull final ComponentItem replacement) {
    LOG.assertTrue(myItems.contains(itemToBeReplaced));

    final int index = myItems.indexOf(itemToBeReplaced);
    myItems.set(index, replacement);
  }

  /** Removed specified {@link ComponentItem} from the group.*/
  public void removeItem(@NotNull final ComponentItem item){
    LOG.assertTrue(myItems.contains(item));

    myItems.remove(item);
  }

  public boolean containsItemClass(@NotNull final String className){
    for(int i = myItems.size() - 1; i >= 0; i--){
      if(className.equals(myItems.get(i).getClassName())){
        return true;
      }
    }

    return false;
  }

  public boolean containsItemCopy(@NotNull final ComponentItem originalItem, final String className) {
    for(int i = myItems.size() - 1; i >= 0; i--){
      if(className.equals(myItems.get(i).getClassName()) && originalItem != myItems.get(i)) {
        return true;
      }
    }

    return false;
  }

  @Nullable public ActionGroup getPopupActionGroup() {
    return (ActionGroup) ActionManager.getInstance().getAction("GuiDesigner.PaletteGroupPopupMenu");
  }

  @Nullable public Object getData(Project project, String dataId) {
    if (dataId.equals(getClass().getName())) {
      return this;
    }
    return null;
  }

  public void handleDrop(Project project, PaletteItem droppedItem, int index) {
    if (droppedItem instanceof ComponentItem) {
      ComponentItem componentItem = (ComponentItem) droppedItem;
      Palette palette = Palette.getInstance(project);
      int oldIndex = myItems.indexOf(componentItem);
      if (oldIndex >= 0) {
        if (index == -1 || oldIndex == index) return;
        if (oldIndex < index) {
          index--;
        }
        myItems.remove(oldIndex);
      }
      else {
        for(GroupItem groupItem: palette.getGroups()) {
          if (groupItem.myItems.contains(componentItem)) {
            groupItem.removeItem(componentItem);
            break;
          }
        }
      }
      if (index == -1) {
        myItems.add(componentItem);
      }
      else {
        myItems.add(index, componentItem);
      }
      palette.fireGroupsChanged();
    }
  }
}
