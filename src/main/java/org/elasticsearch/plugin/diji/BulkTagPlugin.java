package org.elasticsearch.plugin.diji;

import co.diji.rest.BulkTagRestAction;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.plugins.AbstractPlugin;
import org.elasticsearch.rest.RestModule;

public class BulkTagPlugin extends AbstractPlugin{
    public String name() {
        return "BulkTagPlugin";
    }

    public String description() {
        return "allows bulk (partial) updates";
    }

    @Override public void processModule(Module module){
        if(module instanceof RestModule){
            ((RestModule) module).addRestAction(BulkTagRestAction.class);
        }
    }

}
