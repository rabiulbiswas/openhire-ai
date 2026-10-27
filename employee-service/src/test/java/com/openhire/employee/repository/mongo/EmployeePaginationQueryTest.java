package com.openhire.employee.repository.mongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.openhire.employee.domain.Employee;
import com.openhire.employee.domain.EmployeeTypes.EmployeeStatus;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class EmployeePaginationQueryTest {
    @Test
    @SuppressWarnings("unchecked")
    void usesBoundedTenantScopedKeysetQueryWithoutOffset() {
        var mongo = mock(MongoTemplate.class);
        var repository = new MongoEmployeeRepository(mongo);
        repository.page("tenant-a", EmployeeStatus.PRE_BOARDING, "2026-01-01", "2026-11-01", "employee-z", false, 26);
        var capture = ArgumentCaptor.forClass(Query.class);
        verify(mongo).find(capture.capture(), eq(Employee.class));
        var query = capture.getValue();
        assertThat(query.getLimit()).isEqualTo(26);
        assertThat(query.getSkip()).isZero();
        assertThat(query.getSortObject()).isEqualTo(new Document("joiningDate", -1).append("_id", -1));
        var filters = (List<Document>) query.getQueryObject().get("$and");
        assertThat(filters.get(0)).isEqualTo(new Document("tenantId", "tenant-a"));
        assertThat(filters.get(1)).isEqualTo(new Document("status", EmployeeStatus.PRE_BOARDING));
        assertThat(filters.get(2)).isEqualTo(new Document("joiningDate", new Document("$gte", "2026-01-01")));
        var cursor = (List<Document>) filters.get(3).get("$or");
        assertThat(cursor.getFirst()).isEqualTo(new Document("joiningDate", new Document("$lt", "2026-11-01")));
        repository.page("tenant-a", null, null, "2026-11-01", "employee-z", true, 26);
        verify(mongo, times(2)).find(capture.capture(), eq(Employee.class));
        assertThat(capture.getValue().getSortObject()).isEqualTo(new Document("joiningDate", 1).append("_id", 1));
    }
}
