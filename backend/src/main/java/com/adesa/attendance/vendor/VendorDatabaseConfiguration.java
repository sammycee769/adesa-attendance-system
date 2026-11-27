package com.adesa.attendance.vendor;

import java.lang.reflect.Method;
import javax.sql.DataSource;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.*;

@Configuration(proxyBeanMethods = false)
public class VendorDatabaseConfiguration {
    @Bean public VendorTransactions vendorTransactions(DataSource primary,
            @Value("${vendor.enabled:false}") boolean enabled,
            @Value("${vendor.database.url:}") String url,
            @Value("${vendor.database.username:}") String user,
            @Value("${vendor.database.password:}") String password) {
        return new VendorTransactions(primary, enabled, url, user, password);
    }

    @Bean @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public Advisor vendorTransactionAdvisor(ObjectProvider<VendorTransactions> transactions) {
        var pointcut = new StaticMethodMatcherPointcut() {
            @Override public boolean matches(Method method, Class<?> target) {
                return !java.util.Set.of("available","isDemo").contains(method.getName())
                        && method.getDeclaringClass() != Object.class
                        && (VendorService.class.isAssignableFrom(target) || VendorAdmin.class.isAssignableFrom(target));
            }
        };
        var attribute = new RuleBasedTransactionAttribute();
        attribute.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        var attributes = new NameMatchTransactionAttributeSource();
        attributes.addTransactionalMethod("*", attribute);
        // Resolve lazily so advisor discovery cannot cause a DataSource creation cycle.
        MethodInterceptor advice = invocation ->
                new TransactionInterceptor(transactions.getObject().manager(), attributes).invoke(invocation);
        return new DefaultPointcutAdvisor(pointcut, advice);
    }
}
