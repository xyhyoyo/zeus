package com.ctrip.zeus.restful.resource;

import com.ctrip.zeus.auth.Authorize;
import com.ctrip.zeus.exceptions.ValidationException;
import com.ctrip.zeus.lock.DbLockFactory;
import com.ctrip.zeus.lock.DistLock;
import com.ctrip.zeus.model.entity.*;
import com.ctrip.zeus.restful.message.QueryParamRender;
import com.ctrip.zeus.restful.message.view.*;
import com.ctrip.zeus.service.query.*;
import com.ctrip.zeus.restful.message.ResponseHandler;
import com.ctrip.zeus.restful.message.TrimmedQueryParam;
import com.ctrip.zeus.service.model.*;
import com.ctrip.zeus.service.status.GroupStatusService;
import com.ctrip.zeus.status.entity.GroupServerStatus;
import com.ctrip.zeus.status.entity.GroupStatus;
import com.ctrip.zeus.support.ObjectJsonParser;
import com.ctrip.zeus.support.GenericSerializer;
import com.ctrip.zeus.support.ObjectJsonWriter;
import com.ctrip.zeus.tag.PropertyBox;

import com.ctrip.zeus.tag.entity.Property;
import com.ctrip.zeus.tag.TagBox;
import com.google.common.base.Joiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.*;
import java.util.*;

/**
 * @author:xingchaowang
 * @date: 3/4/2015.
 */
@Component
@Path("/")
public class GroupResource {
    @Resource
    private GroupRepository groupRepository;
    @Resource
    private ArchiveRepository archiveRepository;
    @Resource
    private ResponseHandler responseHandler;
    @Resource
    private DbLockFactory dbLockFactory;
    @Resource
    private GroupCriteriaQuery groupCriteriaQuery;
    @Resource
    private CriteriaQueryFactory criteriaQueryFactory;
    @Resource
    private PropertyBox propertyBox;
    @Resource
    private TagBox tagBox;
    @Resource
    private ViewDecorator viewDecorator;
    @Resource
    private GroupStatusService groupStatusService;

    private final String vGroupAppId = "VirtualGroup";
    private final int TIMEOUT = 1000;

    Logger logger = LoggerFactory.getLogger(this.getClass());

    /**
     * @api {get} /api/groups: Request group information
     * @apiName ListGroups
     * @apiGroup Group
     *
     * @apiParam {long[]} groupId       1,2,3
     * @apiParam {string[]} groupName   a,b,c
     * @apiParam {string[]} appId       1001,1101,1100
     * @apiParam {string[]} ip          10.2.1.2,10.2.1.11
     * @apiParam {string} mode          get {online/offline/redundant} (redundant=online&offline) version
     * @apiParam {string} type          get groups with {info/normal/detail/extended} information
     * @apiParam {string[]} anyTag      union search groups by tags e.g. anyTag=group1,group2
     * @apiParam {string[]} tags        join search groups by tags e.g. tags=group1,group2
     * @apiParam {string[]} anyProp     union search groups by properties(key:value) e.g. anyProp=dc:oy,dc:jq
     * @apiParam {string[]} props       join search groups by properties(key:value) e.g. props=department:hotel,dc:jq
     * @apiParam {any} vs               supported vs property queries, ref /api/vses
     * @apiParam {any} slb              supported slb property queries, ref /api/slbs
     *
     * @apiSuccess {Group[]} groups     group list json object
     */
    @GET
    @Path("/groups")
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    @Authorize(name = "getAllGroups")
    public Response list(@Context HttpHeaders hh,
                         @Context final HttpServletRequest request,
                         @TrimmedQueryParam("mode") final String mode,
                         @TrimmedQueryParam("type") final String type,
                         @Context UriInfo uriInfo) throws Exception {
        QueryEngine queryRender = new QueryEngine(QueryParamRender.extractRawQueryParam(uriInfo), "group", SelectionMode.getMode(mode));
        queryRender.init(true);
        IdVersion[] searchKeys = queryRender.run(criteriaQueryFactory);

        GroupListView listView = new GroupListView();
        for (Group group : groupRepository.list(searchKeys)) {
            listView.add(new ExtendedView.ExtendedGroup(group));
        }
        if (ViewConstraints.EXTENDED.equalsIgnoreCase(type)) {
            viewDecorator.decorate(listView.getList(), "group");
        }

        return responseHandler.handleSerializedValue(ObjectJsonWriter.write(listView, type), hh.getMediaType());
    }

    @GET
    @Path("/vgroups")
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    @Authorize(name = "getAllGroups")
    public Response listVGroups(@Context HttpHeaders hh,
                                @Context HttpServletRequest request,
                                @TrimmedQueryParam("mode") final String mode) throws Exception {
        Set<IdVersion> keys = groupCriteriaQuery.queryAllVGroups(SelectionMode.getMode(mode));

        GroupListView listView = new GroupListView();
        for (Group group : groupRepository.list(keys.toArray(new IdVersion[keys.size()]))) {
            listView.add(new ExtendedView.ExtendedGroup(group));
        }
        return responseHandler.handleSerializedValue(ObjectJsonWriter.write(listView, null), hh.getMediaType());
    }

    @GET
    @Path("/group")
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    @Authorize(name = "getGroupByStatus")
    public Response get(@Context HttpHeaders hh, @Context HttpServletRequest request,
                        @TrimmedQueryParam("type") String type,
                        @TrimmedQueryParam("mode") final String mode,
                        @Context UriInfo uriInfo) throws Exception {
        SelectionMode selectionMode = SelectionMode.getMode(mode);
        QueryEngine queryRender = new QueryEngine(QueryParamRender.extractRawQueryParam(uriInfo), "group", selectionMode);
        queryRender.init(true);
        IdVersion[] searchKeys = queryRender.run(criteriaQueryFactory);

        if (SelectionMode.REDUNDANT == selectionMode) {
            if (searchKeys.length > 2)
                throw new ValidationException("Too many matches have been found after querying.");
        } else {
            if (searchKeys.length > 1)
                throw new ValidationException("Too many matches have been found after querying.");
        }

        GroupListView listView = new GroupListView();
        for (Group group : groupRepository.list(searchKeys)) {
            listView.add(new ExtendedView.ExtendedGroup(group));
        }
        if (ViewConstraints.EXTENDED.equalsIgnoreCase(type)) {
            viewDecorator.decorate(listView.getList(), "group");
        }

        if (listView.getTotal() == 0) throw new ValidationException("Group cannot be found.");
        if (listView.getTotal() == 1) {
            return responseHandler.handleSerializedValue(ObjectJsonWriter.write(listView.getList().get(0), type), hh.getMediaType());
        }

        return responseHandler.handleSerializedValue(ObjectJsonWriter.write(listView, type), hh.getMediaType());
    }

    @POST
    @Path("/group/new")
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, "*/*"})
    @Authorize(name = "addGroup")
    public Response add(@Context HttpHeaders hh, @Context HttpServletRequest request, String requestBody,
                        @QueryParam("force") Boolean force) throws Exception {
        ExtendedView.ExtendedGroup extendedView = ObjectJsonParser.parse(requestBody, ExtendedView.ExtendedGroup.class);
        Group g = ObjectJsonParser.parse(requestBody, Group.class).setVirtual(null);
        trim(g);

        Long checkId = groupCriteriaQuery.queryByName(g.getName());
        if (checkId > 0L)
            throw new ValidationException("Group name " + g.getName() + " has been taken by " + checkId + ".");

        g = groupRepository.add(g, force != null && force);


        try {
            propertyBox.set("status", "deactivated", "group", g.getId());
        } catch (Exception ex) {
        }

        if (extendedView.getProperties() != null) {
            setProperties(g.getId(), extendedView.getProperties());
        }

        if (extendedView.getTags() != null) {
            addTag(g.getId(), extendedView.getTags());
        }

        addHealthProperty(g.getId());

        return responseHandler.handle(g, hh.getMediaType());
    }

    @POST
    @Path("/vgroup/new")
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, "*/*"})
    @Authorize(name = "addGroup")
    public Response addVGroup(@Context HttpHeaders hh, @Context HttpServletRequest request, String requestBody) throws Exception {
        ExtendedView.ExtendedGroup extendedView = ObjectJsonParser.parse(requestBody, ExtendedView.ExtendedGroup.class);
        Group g = ObjectJsonParser.parse(requestBody, Group.class).setVirtual(true).setAppId(vGroupAppId);
        trim(g);

        Long checkId = groupCriteriaQuery.queryByName(g.getName());
        if (checkId > 0L)
            throw new ValidationException("Group name " + g.getName() + " has been taken by " + checkId + ".");

        g = groupRepository.addVGroup(g);


        if (extendedView.getProperties() != null) {
            setProperties(g.getId(), extendedView.getProperties());
        }

        if (extendedView.getTags() != null) {
            addTag(g.getId(), extendedView.getTags());
        }

        return responseHandler.handle(g, hh.getMediaType());
    }

    @POST
    @Path("/group/update")
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, "*/*"})
    @Authorize(name = "updateGroup")
    public Response update(@Context HttpHeaders hh, @Context HttpServletRequest request, String requestBody,
                           @QueryParam("force") Boolean force) throws Exception {
        ExtendedView.ExtendedGroup extendedView = ObjectJsonParser.parse(requestBody, ExtendedView.ExtendedGroup.class);
        Group g = ObjectJsonParser.parse(requestBody, Group.class).setVirtual(null);
        trim(g);

        IdVersion[] check = groupCriteriaQuery.queryByIdAndMode(g.getId(), SelectionMode.OFFLINE_FIRST);
        if (check.length == 0) throw new ValidationException("Group " + g.getId() + " cannot be found.");

        DistLock lock = dbLockFactory.newLock(g.getId() + "_updateGroup");
        lock.lock(TIMEOUT);
        try {
            g = groupRepository.update(g, force != null && force);
        } finally {
            lock.unlock();
        }


        try {
            if (groupCriteriaQuery.queryByIdAndMode(g.getId(), SelectionMode.ONLINE_EXCLUSIVE).length == 1) {
                propertyBox.set("status", "toBeActivated", "group", g.getId());
            }
        } catch (Exception ex) {
        }

        if (extendedView.getProperties() != null) {
            setProperties(g.getId(), extendedView.getProperties());
        }

        if (extendedView.getTags() != null) {
            addTag(g.getId(), extendedView.getTags());
        }

        addHealthProperty(g.getId());

        return responseHandler.handle(g, hh.getMediaType());
    }

    @POST
    @Path("/vgroup/update")
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, "*/*"})
    @Authorize(name = "updateGroup")
    public Response updateVGroup(@Context HttpHeaders hh, @Context HttpServletRequest request, String requestBody) throws Exception {
        ExtendedView.ExtendedGroup extendedView = ObjectJsonParser.parse(requestBody, ExtendedView.ExtendedGroup.class);
        Group g = ObjectJsonParser.parse(requestBody, Group.class).setVirtual(true).setAppId(vGroupAppId);
        trim(g);

        DistLock lock = dbLockFactory.newLock(g.getId() + "_updateGroup");
        lock.lock(TIMEOUT);
        try {
            g = groupRepository.updateVGroup(g);
        } finally {
            lock.unlock();
        }

        if (extendedView.getProperties() != null) {
            setProperties(g.getId(), extendedView.getProperties());
        }

        if (extendedView.getTags() != null) {
            addTag(g.getId(), extendedView.getTags());
        }

        return responseHandler.handle(g, hh.getMediaType());
    }

    @POST
    @Path("/group/updateCheckUri")
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, "*/*"})
    public Response updateCheckUri(@Context HttpHeaders hh, @Context HttpServletRequest request,
                                   String requestBody) throws Exception {
        Group g = ObjectJsonParser.parse(requestBody, Group.class);
        IdVersion[] check = groupCriteriaQuery.queryByIdAndMode(g.getId(), SelectionMode.OFFLINE_FIRST);
        if (check.length == 0) throw new ValidationException("Group " + g.getId() + " cannot be found.");

        DistLock lock = dbLockFactory.newLock(g.getId() + "_updateGroup");
        lock.lock(TIMEOUT);
        try {
            Group orig = groupRepository.getById(g.getId());
            orig.getHealthCheck().setUri(g.getHealthCheck().getUri());
            g = groupRepository.update(orig);
        } finally {
            lock.unlock();
        }

        return responseHandler.handle(g, hh.getMediaType());
    }

    @POST
    @Path("/group/bindVs")
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, "*/*"})
    @Authorize(name = "updateGroup")
    public Response bindVs(@Context HttpHeaders hh, @Context HttpServletRequest request,
                           @QueryParam("update") Boolean update, @QueryParam("force") Boolean force, String bound) throws Exception {
        GroupVsBoundList boundList = ObjectJsonParser.parse(bound, GroupVsBoundList.class);
        if (boundList == null) {
            throw new ValidationException("Could not get any entity. Deserialization might have failed.");
        }
        if (boundList.getGroupId() == null) {
            throw new ValidationException("Property group-id is required.");
        }
        Map<Long, GroupVsBound> boundVsMap = new HashMap<>();
        for (GroupVsBound b : boundList.getBounds()) {
            if (b.getVsId() == null || b.getPath() == null) {
                throw new ValidationException("Property vs-id and path are required.");
            }
            boundVsMap.put(b.getVsId(), b);
        }

        Group target = groupRepository.getById(boundList.getGroupId());
        if (target == null) {
            throw new ValidationException("Group " + boundList.getGroupId() + " cannot be found.");
        }

        final boolean isUpdate = update != null && update;

        DistLock lock = dbLockFactory.newLock(target.getId() + "_updateGroup");
        lock.lock(TIMEOUT);
        try {
            for (GroupVirtualServer e : target.getGroupVirtualServers()) {
                GroupVsBound b = boundVsMap.get(e.getVirtualServer().getId());
                if (b != null) {
                    if (isUpdate) {
                        e.setPath(b.getPath()).setPriority(b.getPriority()).setRedirect(b.getRedirect()).setRewrite(b.getRewrite());
                        boundVsMap.remove(e.getVirtualServer().getId());
                    } else {
                        throw new ValidationException("Bound with vs " + b.getVsId() + " already exists. Use ?update=true parameter to update.");
                    }
                }
            }

            if (isUpdate && boundVsMap.size() > 0) {
                throw new ValidationException("Bound with vs " + Joiner.on(',').join(boundVsMap.keySet()) + " could not be found. No need to update.");
            } else {
                for (GroupVsBound b : boundVsMap.values()) {
                    GroupVirtualServer newBound = new GroupVirtualServer().setVirtualServer(new VirtualServer().setId(b.getVsId()))
                            .setPath(b.getPath()).setPriority(b.getPriority()).setRedirect(b.getRedirect()).setRewrite(b.getRewrite());
                    target.getGroupVirtualServers().add(newBound);
                }
            }

            target = groupRepository.update(target, force != null && force);
            try {
                if (groupCriteriaQuery.queryByIdAndMode(target.getId(), SelectionMode.ONLINE_EXCLUSIVE).length == 1) {
                    propertyBox.set("status", "toBeActivated", "group", target.getId());
                }
            } catch (Exception ex) {
            }
        } finally {
            lock.unlock();
        }

        return responseHandler.handle(target, hh.getMediaType());
    }

    @GET
    @Path("/group/unbindVs")
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, "*/*"})
    @Authorize(name = "updateGroup")
    public Response unbindVs(@Context HttpHeaders hh, @Context HttpServletRequest request,
                             @QueryParam("groupId") Long groupId,
                             @QueryParam("vsId") String vsId) throws Exception {
        if (vsId == null) throw new ValidationException("Parameter groupId and vsId must be provided");

        Group target = groupRepository.getById(groupId);
        if (target == null) {
            throw new ValidationException("Group " + groupId + " cannot be found.");
        }

        Set<Long> unboundVsIds = new HashSet<>();
        for (String s : vsId.split(",")) {
            unboundVsIds.add(Long.parseLong(s));
        }

        DistLock lock = dbLockFactory.newLock(target.getName() + "_updateGroup");
        lock.lock(TIMEOUT);
        try {
            Iterator<GroupVirtualServer> iter = target.getGroupVirtualServers().iterator();
            while (iter.hasNext()) {
                GroupVirtualServer e = iter.next();
                if (unboundVsIds.contains(e.getVirtualServer().getId())) iter.remove();
            }

            if (target.getGroupVirtualServers().size() == 0) {
                throw new ValidationException("No bound will exist after unbinding. Request is rejected.");
            }

            target = groupRepository.update(target, true);
            try {
                if (groupCriteriaQuery.queryByIdAndMode(target.getId(), SelectionMode.ONLINE_EXCLUSIVE).length == 1) {
                    propertyBox.set("status", "toBeActivated", "group", target.getId());
                }
            } catch (Exception ex) {
            }
        } finally {
            lock.unlock();
        }

        return responseHandler.handle(target, hh.getMediaType());
    }


    @GET
    @Path("/group/delete")
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    @Authorize(name = "deleteGroup")
    public Response delete(@Context HttpHeaders hh, @Context HttpServletRequest request,
                           @QueryParam("groupId") Long groupId,
                           @QueryParam("groupName") String groupName) throws Exception {
        if (groupId == null) {
            if (groupName != null && !groupName.isEmpty())
                groupId = groupCriteriaQuery.queryByName(groupName);
        }
        if (groupId == null)
            throw new ValidationException("Query parameter - groupId is not provided or could not be found by query.");
        Group archive = groupRepository.getById(groupId);
        if (archive == null) throw new ValidationException("Group cannot be found with id " + groupId + ".");

        groupRepository.delete(groupId);
        try {
            archiveRepository.archiveGroup(archive);
        } catch (Exception ex) {
            logger.warn("Try archive deleted group failed. " + GenericSerializer.writeJson(archive, false), ex);
        }

        try {
            propertyBox.clear("group", groupId);
        } catch (Exception ex) {
        }
        try {
            tagBox.clear("group", groupId);
        } catch (Exception ex) {
        }

        return responseHandler.handle("Group is deleted.", hh.getMediaType());
    }

    @GET
    @Path("/vgroup/delete")
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    @Authorize(name = "deleteGroup")
    public Response deleteVGroup(@Context HttpHeaders hh, @Context HttpServletRequest request, @QueryParam("groupId") Long groupId) throws Exception {
        if (groupId == null)
            throw new Exception("Query parameter - groupId is required.");
        Group archive = groupRepository.getById(groupId);
        if (archive == null) throw new ValidationException("Virtual group cannot be found with id " + groupId + ".");

        groupRepository.deleteVGroup(groupId);
        try {
            archiveRepository.archiveGroup(archive.setVirtual(true));
        } catch (Exception ex) {
            logger.warn("Try archive deleted virtual group failed. " + GenericSerializer.writeJson(archive, false), ex);
        }
        return responseHandler.handle("Virtual group is deleted.", hh.getMediaType());
    }

    private void setProperties(Long groupId, List<Property> properties) {
        for (Property p : properties) {
            try {
                propertyBox.set(p.getName(), p.getValue(), "group", groupId);
            } catch (Exception e) {
                logger.warn("Fail to set property " + p.getName() + "/" + p.getValue() + " on group " + groupId + ".");
            }
        }
    }

    private void addTag(Long groupId, List<String> tags) {
        for (String tag : tags) {
            try {
                tagBox.tagging(tag, "group", new Long[]{groupId});
            } catch (Exception e) {
                logger.warn("Fail to tagging " + tag + " on group " + groupId + ".");
            }
        }
    }

    private void trim(Group g) throws Exception {
        g.setAppId(trimIfNotNull(g.getAppId()));
        g.setName(trimIfNotNull(g.getName()));
        if (g.getHealthCheck() != null)
            g.getHealthCheck().setUri(trimIfNotNull(g.getHealthCheck().getUri()));
        for (GroupServer groupServer : g.getGroupServers()) {
            groupServer.setIp(trimIfNotNull(groupServer.getIp()));
            groupServer.setHostName(trimIfNotNull(groupServer.getHostName()));
        }
        if (g.getLoadBalancingMethod() != null)
            g.getLoadBalancingMethod().setValue(trimIfNotNull(g.getLoadBalancingMethod().getValue()));
    }

    private String trimIfNotNull(String value) {
        return value != null ? value.trim() : value;
    }


    private void addHealthProperty(Long groupId) throws Exception {
        GroupStatus gs = groupStatusService.getOfflineGroupStatus(groupId);
        boolean health = true;
        boolean unhealth = true;
        for (GroupServerStatus gss : gs.getGroupServerStatuses()) {
            if (gss.getServer() && gss.getHealthy() && gss.getPull() && gss.getMember()) {
                unhealth = false;
            } else {
                health = false;
            }
        }
        if (health) {
            propertyBox.set("healthy", "health", "group", gs.getGroupId());
        } else if (unhealth) {
            propertyBox.set("healthy", "unhealth", "group", gs.getGroupId());
        } else {
            propertyBox.set("healthy", "sub-health", "group", gs.getGroupId());
        }

    }
}
