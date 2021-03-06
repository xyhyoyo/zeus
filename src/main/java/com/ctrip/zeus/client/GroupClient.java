package com.ctrip.zeus.client;


import com.ctrip.zeus.model.entity.Group;
import com.ctrip.zeus.model.entity.GroupList;
import com.ctrip.zeus.model.transform.DefaultJsonParser;
import com.ctrip.zeus.support.GenericSerializer;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.List;

/**
 * @author:xingchaowang
 * @date: 3/6/2015.
 */
public class GroupClient extends AbstractRestClient {
    public GroupClient(String url) {
        super(url);
    }

    public List<Group> getAll() {
        String res = getTarget().path("/api/groups").queryParam("type", "detail").request().headers(getDefaultHeaders()).get(String.class);
        try {
            return DefaultJsonParser.parse(GroupList.class, res).getGroups();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Group get(String groupName) {
        String res = getTarget().path("/api/group").queryParam("groupName", groupName).queryParam("type", "detail").request(MediaType.APPLICATION_JSON)
                .headers(getDefaultHeaders()).get(String.class);
        try {
            return DefaultJsonParser.parse(Group.class, res);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Group> getGroupsByVsId(String vsId) {
        String res = getTarget().path("/api/groups").queryParam("vsId", vsId).request().headers(getDefaultHeaders()).get(String.class);
        try {
            return DefaultJsonParser.parse(GroupList.class, res).getGroups();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Response add(Group group) {
        return getTarget().path("/api/group/new").request().headers(getDefaultHeaders())
                .post(Entity.entity(
                        GenericSerializer.writeJson(group), MediaType.APPLICATION_JSON));

    }

    public Response update(Group group) {
        return getTarget().path("/api/group/update").request().headers(getDefaultHeaders())
                .post(Entity.entity(
                        GenericSerializer.writeJson(group), MediaType.APPLICATION_JSON));

    }

    public Response delete(Long groupId) {
        return getTarget().path("/api/group/delete").queryParam("groupId", groupId).request(MediaType.APPLICATION_JSON)
                .headers(getDefaultHeaders()).get();
    }
}
