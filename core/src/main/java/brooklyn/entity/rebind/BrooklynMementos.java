package brooklyn.entity.rebind;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

import brooklyn.management.ManagementContext;
import brooklyn.mementos.BrooklynMemento;
import brooklyn.mementos.EntityMemento;
import brooklyn.mementos.LocationMemento;
import brooklyn.mementos.TreeNode;

public class BrooklynMementos {

    private static final BrooklynMemento EMPTY_MEMENTO = new EmptyBrooklynMemento();

    private BrooklynMementos() {}
    
    public static BrooklynMemento emptyMemento() {
        return EMPTY_MEMENTO;
    }

    public static BrooklynMemento newMemento(ManagementContext managementContext) {
        BrooklynMementoImpl result = new BrooklynMementoImpl(managementContext, managementContext.getApplications());
        BrooklynMementos.validateMemento(result);
        return result;
    }
    
    public static void validateMemento(BrooklynMemento memento) {
        // TODO Could also validate integrity of entityReferenceAttributes and entityReferenceConfig
        
        Collection<String> locationIds = memento.getLocationIds();
        
        // Ensure every entity's parent/children/locations exists
        validateParentChildRelations(memento.getLocationMementos());
        validateParentChildRelations(memento.getEntityMementos());
        
        for (String id : memento.getEntityIds()) {
            EntityMemento entityMemento = memento.getEntityMemento(id);
            for (String location : entityMemento.getLocations()) {
                if (!locationIds.contains(location)) {
                    throw new IllegalStateException("Location "+location+" missing, for entity "+entityMemento);
                }
            }
        }
    }
    
    private static void validateParentChildRelations(Map<String, ? extends TreeNode> nodes) {
        for (Map.Entry<String, ? extends TreeNode> entry : nodes.entrySet()) {
            String id = entry.getKey();
            TreeNode node = entry.getValue();
            if (node.getParent() != null && !nodes.containsKey(node.getParent())) {
                throw new IllegalStateException("Parent "+node.getParent()+" missing, for "+node);
            }
            for (String childId : node.getChildren()) {
                if (childId == null) {
                    throw new IllegalStateException("Null child, for "+node);
                }
                if (!nodes.containsKey(childId)) {
                    throw new IllegalStateException("Child "+childId+" missing, for "+node);
                }
            }
        }
    }

    private static class EmptyBrooklynMemento implements BrooklynMemento {
        private static final long serialVersionUID = 6869933736999244363L;

        @Override public EntityMemento getEntityMemento(String id) {
            return null;
        }
        @Override public LocationMemento getLocationMemento(String id) {
            return null;
        }
        @Override public Collection<String> getApplicationIds() {
            return Collections.emptySet();
        }
        @Override public Collection<String> getTopLevelLocationIds() {
            return Collections.emptySet();
        }
        @Override public Collection<String> getEntityIds() {
            return Collections.emptySet();
        }
        @Override public Collection<String> getLocationIds() {
            return Collections.emptySet();
        }
        @Override
        public Map<String, EntityMemento> getEntityMementos() {
            return Collections.emptyMap();
        }
        @Override
        public Map<String, LocationMemento> getLocationMementos() {
            return Collections.emptyMap();
        }
    }
}
