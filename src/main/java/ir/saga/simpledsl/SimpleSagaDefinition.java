package ir.saga.simpledsl;


import ir.saga.command.common.ReplyMessageHeaders;
import ir.saga.common.SagaData;
import ir.saga.message.Message;
import ir.saga.orchestration.SagaActions;
import ir.saga.orchestration.SagaDefinition;
import ir.saga.util.JSonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

public class SimpleSagaDefinition<Data extends SagaData> implements SagaDefinition<Data> {
    private Logger logger = LoggerFactory.getLogger(getClass());
    private List<SagaStep<Data>> sagaSteps;

    public SimpleSagaDefinition(List<SagaStep<Data>> sagaSteps) {
        this.sagaSteps = sagaSteps;
    }

    @Override
    public SagaActions<Data> start(Data sagaData) {
        SagaExecutionState currentState = new SagaExecutionState(-1, false);

        StepToExecute<Data> stepToExecute = nextStepToExecute(currentState, sagaData);

        if (stepToExecute.isEmpty()) {
            return makeEndStateSagaActions(currentState);
        } else
            return stepToExecute.executeStep(sagaData, currentState);
    }

    @Override
    public SagaActions<Data> handleReply(String currentState, Data sagaData, Message message) {

        SagaExecutionState state = SagaExecutionStateJsonSerde.decodeState(currentState);
        SagaStep<Data> currentStep = sagaSteps.get(state.getCurrentlyExecuting());
        boolean compensating = state.isCompensating();

        currentStep.getReplyHandler(message, compensating).ifPresent(handler -> {
            invokeReplyHandler(message, sagaData, handler);
        });

        if (currentStep.isSuccessfulReply(compensating, message)) {
            return executeNextStep(sagaData, state);
        } else if (compensating) {
            throw new UnsupportedOperationException("Failure when compensating");
        } else {
            return executeNextStep(sagaData, state.startCompensating());
        }
    }


    private StepToExecute<Data> nextStepToExecute(SagaExecutionState state, Data data) {
        int skipped = 0;
        boolean compensating = state.isCompensating();
        int direction = compensating ? -1 : +1;
        for (int i = state.getCurrentlyExecuting() + direction; i >= 0 && i < sagaSteps.size(); i = i + direction) {
            SagaStep<Data> step = sagaSteps.get(i);
            if ((compensating ? step.hasCompensation(data) : step.hasAction(data))) {
                return new StepToExecute<>(Optional.of(step), skipped, compensating);
            } else
                skipped++;
        }
        return new StepToExecute<>(Optional.empty(), skipped, compensating);
    }

    private SagaActions<Data> executeNextStep(Data data, SagaExecutionState state) {
        StepToExecute<Data> stepToExecute = nextStepToExecute(state, data);
        logger.info("====> WHAT STATE ");
        if (stepToExecute.isEmpty()) {
            logger.info("====> END STATE ");
            return makeEndStateSagaActions(state);
        } else {
            // do something
            logger.info("====> NEXT STATE ");
            return stepToExecute.executeStep(data, state);
        }
    }

    private void invokeReplyHandler(Message message, Data data, BiConsumer<Data, Object> handler) {
        Class m;
        try {
            m = Class.forName(message.getRequiredHeader(ReplyMessageHeaders.REPLY_TYPE));
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        Object reply = JSonMapper.fromJson(message.getPayload(), m);
        handler.accept(data, reply);
    }

    private SagaActions<Data> makeEndStateSagaActions(SagaExecutionState state) {
        return SagaActions.<Data>builder()
                .withUpdatedState(SagaExecutionStateJsonSerde.encodeState(SagaExecutionState.makeEndState()))
                .withIsEndState(true)
                .withIsCompensating(state.isCompensating())
                .build();
    }


}
