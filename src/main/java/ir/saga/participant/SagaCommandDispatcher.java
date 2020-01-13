package ir.saga.participant;


import ir.saga.command.LockTarget;
import ir.saga.command.SagaCommandHeaders;
import ir.saga.command.SagaLockManager;
import ir.saga.command.SagaUnlockCommand;
import ir.saga.command.common.CommandMessageHeaders;
import ir.saga.command.common.paths.PathVariables;
import ir.saga.command.consumer.CommandDispatcher;
import ir.saga.command.consumer.CommandHandler;
import ir.saga.command.consumer.CommandHandlers;
import ir.saga.command.consumer.CommandMessage;
import ir.saga.common.SagaReplyHeaders;
import ir.saga.common.StashMessageRequiredException;
import ir.saga.message.Message;
import ir.saga.message.MessageBuilder;
import ir.saga.message.consumer.MessageConsumer;
import ir.saga.message.producer.MessageProducer;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class SagaCommandDispatcher extends CommandDispatcher {

    private SagaLockManager sagaLockManager;

    public SagaCommandDispatcher(String commandDispatcherId,
                                 CommandHandlers target,
                                 MessageConsumer messageConsumer,
                                 MessageProducer messageProducer,
                                 SagaLockManager sagaLockManager) {
        super(commandDispatcherId, target, messageConsumer, messageProducer);
        this.sagaLockManager = sagaLockManager;
    }

    @Override
    public void messageHandler(String channel, Message message) {
        if (isUnlockMessage(message)) {
            String sagaType = getSagaType(message);
            String sagaId = getSagaId(message);
            String target = message.getRequiredHeader(CommandMessageHeaders.RESOURCE);
            sagaLockManager.unlock(sagaId, target, message.getSecurityToken()).ifPresent(m -> super.messageHandler(channel, message));
        } else {
            try {
                super.messageHandler(channel, message);
            } catch (StashMessageRequiredException e) {
                String sagaType = getSagaType(message);
                String sagaId = getSagaId(message);
                String target = e.getTarget();
                sagaLockManager.stashMessage(sagaType, sagaId, target, message);
            }
        }
    }

    private String getSagaId(Message message) {
        return message.getRequiredHeader(SagaCommandHeaders.SAGA_ID);
    }

    private String getSagaType(Message message) {
        return message.getRequiredHeader(SagaCommandHeaders.SAGA_TYPE);
    }


    @Override
    protected List<Message> invoke(CommandHandler commandHandler, CommandMessage cm, Map<String, String> pathVars) {
        Optional<String> lockedTarget = Optional.empty();
        if (commandHandler instanceof SagaCommandHandler) {
            SagaCommandHandler sch = (SagaCommandHandler) commandHandler;
            if (sch.getPreLock().isPresent()) {
                LockTarget lockTarget = sch.getPreLock().get().apply(cm, new PathVariables(pathVars)); // TODO
                Message message = cm.getMessage();
                String sagaType = getSagaType(message);
                String sagaId = getSagaId(message);
                String target = lockTarget.getTarget();
                lockedTarget = Optional.of(target);
                if (!sagaLockManager.claimLock(sagaType, sagaId, target))
                    throw new StashMessageRequiredException(target);
            }
        }

        List<Message> messages = super.invoke(commandHandler, cm, pathVars);

        if (lockedTarget.isPresent())
            return addLockedHeader(messages, lockedTarget.get());
        else {
            Optional<LockTarget> lt = getLock(messages);
            if (lt.isPresent()) {
                Message message = cm.getMessage();
                String sagaType = getSagaType(message);
                String sagaId = getSagaId(message);
                Assert.isTrue(sagaLockManager.claimLock(sagaType, sagaId, lt.get().getTarget()));
                return addLockedHeader(messages, lt.get().getTarget());
            } else
                return messages;
        }
    }

    private Optional<LockTarget> getLock(List<Message> messages) {
        return messages.stream().filter(m -> m instanceof SagaReplyMessage && ((SagaReplyMessage) m).hasLockTarget()).findFirst().flatMap(m -> ((SagaReplyMessage) m).getLockTarget());
    }

    private List<Message> addLockedHeader(List<Message> messages, String lockedTarget) {
        // TODO - what about the isEmpty case??
        // TODO - sagas must return messages
        return messages.stream().map(m -> MessageBuilder.withMessage(m).withHeader(SagaReplyHeaders.REPLY_LOCKED, lockedTarget).build(m.getSecurityToken())).collect(Collectors.toList());
    }


    private boolean isUnlockMessage(Message message) {
        return message.getRequiredHeader(CommandMessageHeaders.COMMAND_TYPE).equals(SagaUnlockCommand.class.getName());
    }

}
