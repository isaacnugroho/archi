/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package com.archimatetool.editor.views.tree.commands;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.commands.CompoundCommand;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

import com.archimatetool.editor.diagram.commands.DiagramCommandFactory;
import com.archimatetool.editor.model.DiagramModelUtils;
import com.archimatetool.editor.model.commands.DeleteArchimateElementCommand;
import com.archimatetool.editor.model.commands.DeleteArchimateRelationshipCommand;
import com.archimatetool.editor.model.commands.DeleteDiagramModelCommand;
import com.archimatetool.editor.model.commands.DeleteFolderCommand;
import com.archimatetool.editor.views.tree.TreeModelViewer;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IAdapter;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateModelObject;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IDiagramModelReference;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.util.ArchimateModelUtils;



/**
 * Handles Delete Commands for the Tree Model View. <br/>
 * This is intended to manage element/folder deletions specifically for the Models Tree. <br/>
 * <br/>
 * - Will select a parent node in the tree after the deletion <br/>
 * - Manages deletions from more than one model - each model will have a separate command stack <br/>
 * - Deletes associated relationships <br/>
 * - Deletes associate diagram objects <br/>
 * 
 * @author Phillip Beauvoir
 */
public class DeleteCommandHandler {
    
    /*
     * If deleting elements from more than one model in the tree we need to use the
     * Command Stack allocated to each model. And then allocate one CompoundCommand per Command Stack.
     */
    private Hashtable<CommandStack, CompoundCommand> fCommandMap = new Hashtable<>();
    
    // Treeviewer
    private TreeModelViewer fViewer;
    
    // Selected objects in Tree
    private Object[] fSelectedObjects = new Object[1]; // default value
    
    // Objects to delete
    private Set<IArchimateModelObject> fObjectsToDelete;
    
    // The object to select in the tree after the deletion
    private Object fObjectToSelectAfterDeletion;
    
    // A cache for a model's diagram references as getting them is slow if there are many diagrams
    private Map<IArchimateModel, List<IDiagramModelReference>> fDiagramModelReferenceCache;
    
    /**
     * @param element
     * @return True if we can delete this object
     */
    public static boolean canDelete(Object element) {
        // Elements, Relations, Diagrams and user folders
        return element instanceof IArchimateConcept || element instanceof IDiagramModel ||
                (element instanceof IFolder folder && folder.getType().equals(FolderType.USER));
    }

    /**
     * @param objects Objects to delete
     */
    public DeleteCommandHandler(Object[] objects) {
        this(null, objects);
    }

    /**
     * @param viewer Tree Viewer in case of selecting a tree node after deletion
     * @param objects Objects to delete
     */
    public DeleteCommandHandler(TreeModelViewer viewer, Object[] objects) {
        fViewer = viewer;
        fSelectedObjects = objects;
    }
    
    /**
     * @return True if any of the objects to be deleted are referenced in a diagram
     */
    public boolean hasDiagramReferences() {
        for(Object object : fSelectedObjects) {
            if(hasDiagramReferences(object)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * @return True if object has references in a diagram model
     */
    private boolean hasDiagramReferences(Object object) {
        if(object instanceof IFolder folder) {
            for(EObject element : folder.getElements()) {
                if(hasDiagramReferences(element)) {
                    return true;
                }
            }
            for(IFolder subFolder : folder.getFolders()) {
                if(hasDiagramReferences(subFolder)) {
                    return true;
                }
            }
        }
        
        // Concept
        if(object instanceof IArchimateConcept concept) {
            return DiagramModelUtils.isArchimateConceptReferencedInDiagrams(concept);
        }
        
        // Diagram Model Reference
        if(object instanceof IDiagramModel dm) {
            return DiagramModelUtils.hasDiagramModelReference(dm);
        }
        
        return false;
    }
    
    /**
     * Delete the objects.
     * Once this occurs this DeleteCommandHandler is disposed.
     */
    public void delete() {
        // Find the object to select after the deletion
        fObjectToSelectAfterDeletion = findObjectToSelectAfterDeletion();
        
        // Actual elements to delete
        fObjectsToDelete = new HashSet<>();
        
        fDiagramModelReferenceCache = new HashMap<>();
        
        // Gather the objects to delete
        getObjectsToDelete();
        
        // Create the Commands
        createCommands();
        
        // Execute the Commands on the CommandStack(s) - there could be more than one if more than one model open in the Tree
        for(Entry<CommandStack, CompoundCommand> entry : fCommandMap.entrySet()) {
            entry.getKey().execute(entry.getValue());
        }
        
        dispose();
    }
    
    /**
     * Create the Delete Commands
     */
    private void createCommands() {
        /*
         *  We need to ensure that the Delete Diagram Model Commands are called first in order to close
         *  any open diagram editors before removing their models from parent folders.
         */
        for(Object object : fObjectsToDelete) {
            if(object instanceof IDiagramModel dm) {
                CompoundCommand compoundCommand = getCompoundCommand(dm);
                if(compoundCommand != null) {
                    Command cmd = new DeleteDiagramModelCommand(dm);
                    compoundCommand.add(cmd);
                }
                else {
                    System.err.println("Could not get CompoundCommand in " + getClass()); //$NON-NLS-1$
                }
            }
        }
        
        /*
         * Then the other types
         */
        for(Object object : fObjectsToDelete) {
            if(object instanceof IDiagramModel) { // already done
                continue;
            }
            
            CompoundCommand compoundCommand = getCompoundCommand((IAdapter)object);
            if(compoundCommand == null) { // sanity check
                System.err.println("Could not get CompoundCommand in " + getClass()); //$NON-NLS-1$
                continue;
            }

            if(object instanceof IFolder folder) {
                Command cmd = new DeleteFolderCommand(folder);
                compoundCommand.add(cmd);
            }
            else if(object instanceof IArchimateElement element) {
                Command cmd = new DeleteArchimateElementCommand(element);
                compoundCommand.add(cmd);
            }
            else if(object instanceof IArchimateRelationship relationship) {
                Command cmd = new DeleteArchimateRelationshipCommand(relationship);
                compoundCommand.add(cmd);
            }
            else if(object instanceof IDiagramModelObject dmo) {
                Command cmd = DiagramCommandFactory.createDeleteDiagramObjectCommand(dmo);
                compoundCommand.add(cmd);
            }
            else if(object instanceof IDiagramModelConnection dmc) {
                Command cmd = DiagramCommandFactory.createDeleteDiagramConnectionCommand(dmc);
                compoundCommand.add(cmd);
            }
        }
    }
    
    /**
     * Create the list of objects to delete and check
     * @return
     */
    private void getObjectsToDelete() {
        // First, get the Archimate concepts and folders to be deleted
        for(Object object : fSelectedObjects) {
            if(canDelete(object)) {
                fObjectsToDelete.add((IArchimateModelObject)object);
                addFolderChildElements(object);
                addElementRelationships(object);
            }
        }
        
        // Then get the referenced diagram components to be deleted
        for(IArchimateModelObject object : new ArrayList<>(fObjectsToDelete)) {
            // Archimate Concept to be deleted
            if(object instanceof IArchimateConcept concept) {
                fObjectsToDelete.addAll(concept.getReferencingDiagramComponents());
            }
            
            // Diagram Model to be deleted so we also need to delete diagram model references, if any
            if(object instanceof IDiagramModel dm) {
                getDiagramModelReferencesToDelete(dm);
            }
        }
    }
    
    private void getDiagramModelReferencesToDelete(IDiagramModel dm) {
        // Iterating more than once can be slow so use a cache of diagram model references
        List<IDiagramModelReference> refs = fDiagramModelReferenceCache.get(dm.getArchimateModel());
        
        // Not in the cache so create a new one
        if(refs == null) {
            refs = new ArrayList<>();
            IFolder diagramsFolder = dm.getArchimateModel().getFolder(FolderType.DIAGRAMS);
            if(diagramsFolder != null) {
                for(Iterator<EObject> iter = diagramsFolder.eAllContents(); iter.hasNext();) {
                    EObject eObject = iter.next();
                    if(eObject instanceof IDiagramModelReference ref) {
                        refs.add(ref);
                    }
                }
            }
            fDiagramModelReferenceCache.put(dm.getArchimateModel(), refs);
        }
        
        // Iterate through all refs
        for(IDiagramModelReference ref : refs) {
            if(ref.getReferencedModel() == dm) {
                fObjectsToDelete.add(ref);
            }
        }
    }

    /**
     * Gather elements in folders that need checking for referenced diagram objects and other checks
     */
    private void addFolderChildElements(Object object) {
        // Folder
        if(object instanceof IFolder folder) {
            for(EObject element : folder.getElements()) {
                addFolderChildElements(element);
            }

            // Child folders
            for(IFolder childFolder : folder.getFolders()) {
                addFolderChildElements(childFolder);
            }
        }
        else if(object instanceof IArchimateModelObject amo) {
            fObjectsToDelete.add(amo);
        }
    }
    
    /**
     * Add any connected relationships for an Element
     */
    private void addElementRelationships(Object object) {
        // Folder
        if(object instanceof IFolder folder) {
            for(EObject element : folder.getElements()) {
                addElementRelationships(element);
            }

            // Child folders
            for(IFolder childFolder : folder.getFolders()) {
                addElementRelationships(childFolder);
            }
        }
        // Element/Relation
        else if(object instanceof IArchimateConcept concept) {
            for(IArchimateRelationship relationship : ArchimateModelUtils.getAllRelationshipsForConcept(concept)) {
                fObjectsToDelete.add(relationship);
                
                // Recurse
                addElementRelationships(relationship);
            }
        }
    }
    
    /**
     * Get, and if need be create, a CompoundCommand to which to add the object to be deleted command
     */
    private CompoundCommand getCompoundCommand(IAdapter object) {
        // Get the Command Stack registered to the object
        CommandStack stack = (CommandStack)object.getAdapter(CommandStack.class);
        if(stack == null) {
            System.err.println("CommandStack was null in " + getClass()); //$NON-NLS-1$
            return null;
        }
        
        // Now get or create a Compound Command
        CompoundCommand compoundCommand = fCommandMap.get(stack);
        if(compoundCommand == null) {
            compoundCommand = new DeleteElementsCompoundCommand(fObjectToSelectAfterDeletion);
            fCommandMap.put(stack, compoundCommand);
        }
        
        return compoundCommand;
    }
    
    /**
     * Find the best object to select after the deletion
     */
    private Object findObjectToSelectAfterDeletion() {
        if(fViewer == null) {
            return null;
        }
        
        Object firstObject = fSelectedObjects[0];
        
        TreeItem item = fViewer.findTreeItem(firstObject);
        if(item == null) {
            return null;
        }
        
        TreeItem parentTreeItem = item.getParentItem();
        
        // Parent Item not found so must be at top level
        if(parentTreeItem == null) {
            Tree tree = item.getParent();
            int index = tree.indexOf(item);
            if(index < 1) { // At root or not found
                return null;
            }
            return tree.getItem(index - 1).getData();
        }

        Object selected = null;
        
        // Item index is greater than 0 so select previous sibling item
        int index = parentTreeItem.indexOf(item);
        if(index > 0) {
            selected = parentTreeItem.getItem(index - 1).getData();
        }
        
        // Was null so select parent of first object
        if(selected == null && firstObject instanceof EObject eObject) {
            selected = eObject.eContainer();
        }
        
        return selected;
    }
    
    private void dispose() {
        fSelectedObjects = null;
        fObjectsToDelete = null;
        fViewer = null;
        fCommandMap = null;
        fDiagramModelReferenceCache = null;
        fObjectToSelectAfterDeletion = null;
    }
}
