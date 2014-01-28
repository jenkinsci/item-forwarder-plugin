package org.jenkinsci.plugins.item_forwarding;

import hudson.Extension;
import hudson.Util;
import hudson.model.*;
import hudson.util.AlternativeUiTextProvider;
import hudson.util.FormApply;
import hudson.util.FormValidation;
import jenkins.model.AbstractTopLevelItem;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.stapler.*;
import org.kohsuke.stapler.interceptor.RequirePOST;

import javax.servlet.ServletException;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.logging.Logger;

public class ForwardingItem extends AbstractTopLevelItem {

    static final Logger LOGGER = Logger.getLogger(ForwardingItem.class.getName());

    private String referredItemName;

    public boolean isForwardImmediately() {
        return forwardImmediately;
    }

    public void setForwardImmediately(boolean forwardImmediately) {
        this.forwardImmediately = forwardImmediately;
    }

    private boolean forwardImmediately;

    public ForwardingItem(ItemGroup group, String name) {
        super(group, name);
    }

    public Item getReferredItem() {
        if (referredItemName == null) {
            return null;
        }
        Item i = Jenkins.getInstance().getItemByFullName(referredItemName);
        return i;
    }

    public String getReferredItemName() {
        return referredItemName;
    }

    public void setReferredItemName(String referredItemName) {
        this.referredItemName = referredItemName;
    }

    public boolean isReferredItemExists() {
        return getReferredItem() != null;
    }

    @Override
    public String getPronoun() {
        return AlternativeUiTextProvider.get(PRONOUN, this, Messages.ForwardingItem_DefaultPronoun());
    }

    public void doIndex(StaplerRequest req, StaplerResponse rsp) throws IOException {
        String forwardUrl = getReferredUrl();
        if (forwardImmediately && forwardUrl != null) {
            throw HttpResponses.redirectViaContextPath(301, forwardUrl);
        }
        throw new ForwardToView(this, "_index.jelly").with("path", req.getParameter("path"));
    }

    @RequirePOST
    public void doDoRename(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {

        if (!hasPermission(CONFIGURE)) {
            // rename is essentially delete followed by a create
            checkPermission(CREATE);
            checkPermission(DELETE);
        }

        String newName = req.getParameter("newName");
        Jenkins.checkGoodName(newName);

        renameTo(newName);
        rsp.sendRedirect2("../" + newName);
    }

    public TopLevelItemDescriptor getDescriptor() {
        return (DescriptorImpl) Jenkins.getInstance().getDescriptorOrDie(getClass());
    }

    @Override
    public String getDisplayName() {
        return getName() + " Forwarder";
    }

    public String getReferredUrl() {
        Item i = this.getReferredItem();
        if (i == null) {
            return null;
        }
        String url = StringUtils.appendIfMissing(this.getReferredItem().getUrl(), "/");
        String path = StringUtils.removeStart(Stapler.getCurrentRequest().getParameter("path"), "/");
        if (path != null) {
            return url + path;
        }
        return url;
    }

    public void doDynamic(StaplerRequest req, StaplerResponse rsp) throws IOException {
        rsp.sendRedirect(getAbsoluteUrl() + "?path=" + URLEncoder.encode(req.getRestOfPath(), "UTF-8"));
    }

    @RequirePOST
    public void doConfigSubmit(StaplerRequest req, StaplerResponse rsp)
            throws IOException, ServletException, Descriptor.FormException {
        checkPermission(CONFIGURE);

        req.setCharacterEncoding("UTF-8");
        JSONObject json = req.getSubmittedForm();

        String newReferredItemName = Util.fixEmpty(json.optString("referredItemName"));

        // prevent invalid configuration with bad failure modes (StackOverflowException etc.)
        FormValidation result = ((DescriptorImpl)getDescriptor()).doCheckReferredItemName(newReferredItemName, this);
        if (result.kind == FormValidation.Kind.ERROR) {
            throw new IllegalArgumentException(result.getMessage(), result);
        }

        description = json.getString("description");

        referredItemName = newReferredItemName;
        forwardImmediately = json.optBoolean("forwardImmediately", false);

        save();

        String newName = json.getString("name");
        if (newName != null && !newName.equals(name)) {
            Hudson.checkGoodName(newName);
            rsp.sendRedirect("rename?newName=" + URLEncoder.encode(newName, "UTF-8"));
        } else {
            FormApply.success(".").generateResponse(req, rsp, this);
        }
    }

    @Extension
    public static class DescriptorImpl extends TopLevelItemDescriptor {

        public ForwardingItem newInstance(ItemGroup parent, String name) {
            return new ForwardingItem(parent, name);
        }

        public String getDisplayName() {
            return Messages.ForwardingItem_DisplayName();
        }

        public FormValidation doCheckReferredItemName(@QueryParameter String value, @AncestorInPath ForwardingItem item) {
            LOGGER.fine("Checking forwarded item name " + value);
            if (value.equals(item.name)) {
                return FormValidation.error("Cannot use own name as forwarded item name.");
            }

            Item i = Jenkins.getInstance().getItemByFullName(value);
            while (i instanceof ForwardingItem) {
                if (i == item) {
                    return FormValidation.error("Detected infinite loop of forwarded items.");
                }
                i = Jenkins.getInstance().getItemByFullName(((ForwardingItem) i).getReferredItemName());
            }
            if (i == null) {
                return FormValidation.error("No item with that name found. You always need to specify the full name.");
            }
            return FormValidation.ok();
        }

        public AutoCompletionCandidates doAutoCompleteReferredItemName(@QueryParameter final String value) {
            final AutoCompletionCandidates r = new AutoCompletionCandidates();

            new ItemVisitor() {
                @Override
                public void onItemGroup(ItemGroup<?> group) {
                    // only dig deep when the path matches what's typed.
                    // for example, if 'foo/bar' is typed, we want to show 'foo/barcode'
                    if (value.startsWith(group.getFullName())) {
                        LOGGER.fine("Visiting item group " + group.getFullName());
                        super.onItemGroup(group);
                    }
                }

                @Override
                public void onItem(Item i) {
                    LOGGER.fine("Visiting " + i.getFullName() + " checking if it starts with " + value);
                    if (i.getFullName().startsWith(value)) {
                        if (i instanceof ForwardingItem) {
                            // don't suggest other forwarders
                            LOGGER.fine("Skipping " + i.getFullName() + " as it's a forwarder");
                            return;
                        }
                        r.add((i.getFullName()));
                        super.onItem(i);
                        return;
                    }
                    // if the user typed something more specific that this item group's full name, visit it anyway
                    // otherwise, no nesting autocompletion beyond the first item group name
                    if (value.startsWith(i.getFullName()) && i instanceof ItemGroup) {
                        super.onItem(i);
                    }
                }
            }.onItemGroup(Jenkins.getInstance());

            return r;
        }
    }

    public static class ForwardingItemIcon extends AbstractStatusIcon {

        public String getImageOf(String s) {
            return Stapler.getCurrentRequest().getContextPath() + Hudson.RESOURCE_PATH + "/plugin/item-forwarder/images/" + s + "/forward.png";
        }

        public String getDescription() {
            return "Forwarder";
        }
    }

    public ForwardingItemIcon getIconColor() {
        return new ForwardingItemIcon();
    }

}
