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
@WireMockTest(httpPort = 8081)
class GetTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void run(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        final String tableName = "fakeTableName";

        stubFor(any(urlPathEqualTo("/service-now.com/api/now/table/fakeTableName")).willReturn(okJson(DATA)));
        stubFor(any(urlPathEqualTo("/service-now.com/oauth_token.do")).willReturn(okJson("{\"access_token\":\"token\"}")));

        RunContext runContext = runContextFactory.of(Map.of(
            "table", tableName
        ));

        Get task = Get.builder()
            .table(Property.ofExpression("{{ table }}"))
            .clientId(Property.ofValue("clientId"))
            .clientSecret(Property.ofValue("clientSecret"))
            .username(Property.ofValue("username"))
            .password(Property.ofValue("password"))
            .domain(Property.ofValue("kestra"))
            .uri(wireMockRuntimeInfo.getHttpBaseUrl() + "/service-now.com/") //Used only for testing
            .build();

        var output = task.run(runContext);

        assertThat(output.getSize(), is(1));
        assertThat(output.getResults().getFirst().size(), is(65));
        assertThat(output.getResults().getFirst().get("number"), is("PRB0000050"));
    }

    static final String DATA = """
        {
              "result": [
                {
                  "parent": "",
                  "made_sla": "true",
                  "watch_list": "",
                  "upon_reject": "cancel",
                  "sys_updated_on": "2016-01-19 04:52:04",
                  "approval_history": "",
                  "number": "PRB0000050",
                  "sys_updated_by": "glide.maint",
                  "opened_by": {
                    "link": "https://instance.servicenow.com/api/now/table/sys_user/glide.maint",
                    "value": "glide.maint"
                  },
                  "user_input": "",
                  "sys_created_on": "2016-01-19 04:51:19",
                  "sys_domain": {
                    "link": "https://instance.servicenow.com/api/now/table/sys_user_group/global",
                    "value": "global"
                  },
                  "state": "4",
                  "sys_created_by": "glide.maint",
                  "knowledge": "false",
                  "order": "",
                  "closed_at": "2016-01-19 04:52:04",
                  "cmdb_ci": {
                    "link": "https://instance.servicenow.com/api/now/table/cmdb_ci/55b35562c0a8010e01cff22378e0aea9",
                    "value": "55b35562c0a8010e01cff22378e0aea9"
                  },
                  "delivery_plan": "",
                  "impact": "3",
                  "active": "false",
                  "work_notes_list": "",
                  "business_service": "",
                  "priority": "4",
                  "sys_domain_path": "/",
                  "time_worked": "",
                  "expected_start": "",
                  "rejection_goto": "",
                  "opened_at": "2016-01-19 04:49:47",
                  "business_duration": "1970-01-01 00:00:00",
                  "group_list": "",
                  "work_end": "",
                  "approval_set": "",
                  "wf_activity": "",
                  "work_notes": "",
                  "short_description": "Switch occasionally drops connections",
                  "correlation_display": "",
                  "delivery_task": "",
                  "work_start": "",
                  "assignment_group": "",
                  "additional_assignee_list": "",
                  "description": "Switch occasionally drops connections",
                  "calendar_duration": "1970-01-01 00:02:17",
                  "close_notes": "updated firmware",
                  "sys_class_name": "problem",
                  "closed_by": "",
                  "follow_up": "",
                  "sys_id": "04ce72c9c0a8016600b5b7f75ac67b5b",
                  "contact_type": "phone",
                  "urgency": "3",
                  "company": "",
                  "reassignment_count": "",
                  "activity_due": "",
                  "assigned_to": "",
                  "comments": "",
                  "approval": "not requested",
                  "sla_due": "",
                  "comments_and_work_notes": "",
                  "due_date": "",
                  "sys_mod_count": "1",
                  "sys_tags": "",
                  "escalation": "0",
                  "upon_approval": "proceed",
                  "correlation_id": "",
                  "location": ""
                }
              ]
            }
        """;
}
