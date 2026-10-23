package com.openhire.employee.repository.mongo;

import com.openhire.employee.domain.Employee;
import com.openhire.employee.domain.EmployeeTypes.EmployeeStatus;
import com.openhire.employee.service.port.EmployeePorts.EmployeeRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
class MongoEmployeeRepository implements EmployeeRepository {
    private final MongoTemplate mongo;

    MongoEmployeeRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Employee save(Employee employee) {
        return mongo.save(employee);
    }

    @Override
    public Optional<Employee> findById(String tenantId, String id) {
        return Optional.ofNullable(mongo.findOne(
                Query.query(Criteria.where("tenantId").is(tenantId).and("_id").is(id)), Employee.class));
    }

    @Override
    public Optional<Employee> findByOffer(String tenantId, String offerId) {
        return Optional.ofNullable(mongo.findOne(
                Query.query(
                        Criteria.where("tenantId").is(tenantId).and("offerId").is(offerId)),
                Employee.class));
    }

    @Override
    public Optional<Employee> findByEmail(String tenantId, String email) {
        return Optional.ofNullable(mongo.findOne(
                Query.query(Criteria.where("tenantId").is(tenantId).and("email").is(email))
                        .with(Sort.by(Sort.Direction.DESC, "createdAt")),
                Employee.class));
    }

    @Override
    public List<Employee> list(String tenantId, EmployeeStatus status, String joiningFrom, int limit) {
        var criteria = Criteria.where("tenantId").is(tenantId);
        if (status != null) criteria = criteria.and("status").is(status);
        if (joiningFrom != null) criteria = criteria.and("joiningDate").gte(joiningFrom);
        return mongo.find(
                Query.query(criteria)
                        .with(Sort.by(Sort.Direction.DESC, "joiningDate").and(Sort.by("employeeNo")))
                        .limit(limit),
                Employee.class);
    }

    @Override
    public List<Employee> findPreBoardingDue(String today, int limit) {
        return mongo.find(
                Query.query(Criteria.where("status")
                                .is(EmployeeStatus.PRE_BOARDING)
                                .and("joiningDate")
                                .lte(today))
                        .limit(limit),
                Employee.class);
    }
}
