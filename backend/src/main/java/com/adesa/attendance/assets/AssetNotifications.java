package com.adesa.attendance.assets;

import com.adesa.attendance.config.AccountProperties;
import com.adesa.attendance.employee.Employee;
import com.adesa.attendance.security.AuthenticatedEmployee;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashSet;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Transactional email outbox: no email is sent before its business transaction commits. */
@Service
class AssetNotifications {
    private final EntityManager em;
    private final AccountProperties accounts;

    AssetNotifications(EntityManager em, AccountProperties accounts) {
        this.em = em;
        this.accounts = accounts;
    }

    void queue(String kind, String event, UUID requestId, Asset asset, UUID employeeId,
               String explanation, AuthenticatedEmployee actor) {
        Employee employee = em.find(Employee.class, employeeId);
        var recipients = new LinkedHashSet<String>();
        recipients.add(employee.getEmail());
        em.createQuery("select e.email from Employee e where e.active=true and e.role in :roles", String.class)
                .setParameter("roles", java.util.List.of(com.adesa.attendance.employee.EmployeeRole.ADMIN,
                        com.adesa.attendance.employee.EmployeeRole.IT_ADMIN))
                .getResultList().forEach(recipients::add);
        String section = kind.equals("Asset") ? "requests" : kind.equals("Repair") ? "repairs" : "replacements";
        String subject = "ADESA: " + kind + " request " + event.toLowerCase() + " — " + asset.assetCode;
        String body = kind + " request " + event.toLowerCase() + ".\n\n"
                + "Employee: " + employee.getFullName() + "\n"
                + "Asset: " + asset.assetCode + " (" + asset.brand + " " + asset.model + ")\n"
                + "Request reference: " + requestId + "\n"
                + "Details: " + explanation + "\n\n"
                + "View the request: " + accounts.getPublicUrl().replaceAll("/+$", "") + "/assets/" + section
                + "\n\nSign in to view the request. Asset and replacement approvals do not authorize any other request.";
        for (String recipient : recipients) {
            AssetMail message = new AssetMail();
            message.assetId = asset.id;
            message.requestId = requestId;
            message.eventType = kind.toUpperCase() + "_REQUEST_" + event;
            message.recipientEmail = recipient;
            message.subject = subject;
            message.textBody = body;
            message.createdBy = actor.employeeId();
            message.updatedBy = actor.employeeId();
            em.persist(message);
        }
    }
}
