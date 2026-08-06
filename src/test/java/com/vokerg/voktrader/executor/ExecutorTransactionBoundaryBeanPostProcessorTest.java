package com.vokerg.voktrader.executor;

import com.vokerg.voktrader.marketdata.TickSizeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutorTransactionBoundaryBeanPostProcessorTest {
    @Test
    void executorInvocationUsesNotSupportedPropagation() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(argThat(definition ->
                definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_NOT_SUPPORTED
        ))).thenReturn(transactionStatus);
        BeanFactory beanFactory = mock(BeanFactory.class);
        when(beanFactory.getBean(PlatformTransactionManager.class)).thenReturn(transactionManager);

        ExecutorTransactionBoundaryBeanPostProcessor processor = new ExecutorTransactionBoundaryBeanPostProcessor();
        processor.setBeanFactory(beanFactory);
        PythonExecutorClient target = new RecordingExecutorClient();

        PythonExecutorClient proxied = (PythonExecutorClient) processor.postProcessAfterInitialization(
                target, "pythonExecutorClient"
        );
        ExecutorCapabilitiesResponse response = proxied.capabilities();

        assertThat(response.success()).isFalse();
        verify(transactionManager).getTransaction(argThat(definition ->
                definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_NOT_SUPPORTED
        ));
        verify(transactionManager).commit(transactionStatus);
    }

    private static final class RecordingExecutorClient extends PythonExecutorClient {
        private RecordingExecutorClient() {
            super(
                    new ExecutorProperties(),
                    mock(WebClient.Builder.class),
                    mock(ObjectMapper.class),
                    mock(TickSizeService.class)
            );
        }

        @Override
        public ExecutorCapabilitiesResponse capabilities() {
            return ExecutorCapabilitiesResponse.failure("TEST", "recorded");
        }
    }
}
