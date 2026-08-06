package com.vokerg.voktrader.executor;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;
import org.springframework.transaction.interceptor.MatchAlwaysTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * Wraps every executor-client bean, including test subclasses, in a
 * PROPAGATION_NOT_SUPPORTED transaction interceptor. Remote HTTP work can
 * therefore never run with an ambient database transaction active.
 */
@Component
public class ExecutorTransactionBoundaryBeanPostProcessor implements BeanPostProcessor, BeanFactoryAware {
    private BeanFactory beanFactory;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof PythonExecutorClient)) {
            return bean;
        }

        DefaultTransactionAttribute attribute = new DefaultTransactionAttribute(
                TransactionDefinition.PROPAGATION_NOT_SUPPORTED
        );
        attribute.setName("executor-network-boundary");
        MatchAlwaysTransactionAttributeSource attributeSource = new MatchAlwaysTransactionAttributeSource();
        attributeSource.setTransactionAttribute(attribute);
        TransactionInterceptor interceptor = new TransactionInterceptor(
                beanFactory.getBean(PlatformTransactionManager.class),
                attributeSource
        );

        ProxyFactory proxyFactory = new ProxyFactory(bean);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(interceptor);
        return proxyFactory.getProxy();
    }
}
