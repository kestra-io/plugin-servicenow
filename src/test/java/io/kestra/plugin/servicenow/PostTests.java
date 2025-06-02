package io.kestra.plugin.servicenow;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

@KestraTest
@WireMockTest(httpPort = 8082)
class PostTests {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void run(WireMockRuntimeInfo wmRuntimeInfo) throws Exception {
        final String tableName = "fakeTableName";

        stubFor(any(urlPathEqualTo("/service-now.com/api/now/table/fakeTableName")).willReturn(okJson(DATA)));
        stubFor(any(urlPathEqualTo("/service-now.com/oauth_token.do")).willReturn(okJson("{\"access_token\":\"token\"}")));

        RunContext runContext = runContextFactory.of(Map.of(
            "table", tableName
        ));


        Post task = Post.builder()
            .data(Property.ofValue(Map.of("data", "data")))
            .table(Property.ofExpression("{{ table }}"))
            .clientId(Property.ofValue("clientId"))
            .clientSecret(Property.ofValue("clientSecret"))
            .username(Property.ofValue("username"))
            .password(Property.ofValue("password"))
            .domain(Property.ofValue("kestra"))
            .uri(wmRuntimeInfo.getHttpBaseUrl() + "/service-now.com/") //Used only for testing
            .build();

        var output = task.run(runContext);

        assertThat(output.getResult().size(), is(82));
        assertThat(output.getResult().get("number"), is("INC0010002"));
    }

    static final String DATA = """
            {
              "result": {
                "upon_approval": "proceed",
                "location": "",
                "expected_start": "",
                "reopen_count": "0",
                "close_notes": "",
                "additional_assignee_list": "",
                "impact": "2",
                "urgency": "2",
                "correlation_id": "",
                "sys_tags": "",
                "sys_domain": {
                  "link": "https://instance.servicenow.com/api/now/table/sys_user_group/global",
                  "value": "global"
                },
                "description": "",
                "group_list": "",
                "priority": "3",
                "delivery_plan": "",
                "sys_mod_count": "0",
                "work_notes_list": "",
                "business_service": "",
                "follow_up": "",
                "closed_at": "",
                "sla_due": "",
                "delivery_task": "",
                "sys_updated_on": "2016-01-22 14:28:24",
                "parent": "",
                "work_end": "",
                "number": "INC0010002",
                "closed_by": "",
                "work_start": "",
                "calendar_stc": "",
                "category": "inquiry",
                "business_duration": "",
                "incident_state": "1",
                "activity_due": "",
                "correlation_display": "",
                "company": "",
                "active": "true",
                "due_date": "",
                "assignment_group": {
                  "link": "https://instance.servicenow.com/api/now/table/sys_user_group/287ebd7da9fe198100f92cc8d1d2154e",
                  "value": "287ebd7da9fe198100f92cc8d1d2154e"
                },
                "caller_id": "",
                "knowledge": "false",
                "made_sla": "true",
                "comments_and_work_notes": "",
                "parent_incident": "",
                "state": "1",
                "user_input": "",
                "sys_created_on": "2016-01-22 14:28:24",
                "approval_set": "",
                "reassignment_count": "0",
                "rfc": "",
                "child_incidents": "0",
                "opened_at": "2016-01-22 14:28:24",
                "short_description": "Unable to connect to office wifi",
                "order": "",
                "sys_updated_by": "admin",
                "resolved_by": "",
                "notify": "1",
                "upon_reject": "cancel",
                "approval_history": "",
                "problem_id": "",
                "work_notes": "",
                "calendar_duration": "",
                "close_code": "",
                "sys_id": "c537bae64f411200adf9f8e18110c76e",
                "approval": "not requested",
                "caused_by": "",
                "severity": "3",
                "sys_created_by": "admin",
                "resolved_at": "",
                "assigned_to": "",
                "business_stc": "",
                "wf_activity": "",
                "sys_domain_path": "/",
                "cmdb_ci": "",
                "opened_by": {
                  "link": "https://instance.servicenow.com/api/now/table/sys_user/6816f79cc0a8016401c5a33be04be441",
                  "value": "6816f79cc0a8016401c5a33be04be441"
                },
                "subcategory": "",
                "rejection_goto": "",
                "sys_class_name": "incident",
                "watch_list": "",
                "time_worked": "",
                "contact_type": "phone",
                "escalation": "0",
                "comments": ""
              }
            }
            """;
}
