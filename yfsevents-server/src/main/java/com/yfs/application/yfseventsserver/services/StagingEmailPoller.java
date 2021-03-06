package com.yfs.application.yfseventsserver.services;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yfs.application.yfseventsserver.controller.EmailController;
import com.yfs.application.yfseventsserver.controller.EventController;
import com.yfs.application.yfseventsserver.entity.Email;
import com.yfs.application.yfseventsserver.entity.Event;
import com.yfs.application.yfseventsserver.entity.StagingEmail;
import com.yfs.application.yfseventsserver.entity.VolunteersAccepted;
import com.yfs.application.yfseventsserver.model.EmailStatus;
import com.yfs.application.yfseventsserver.repository.EventDataRepository;
import com.yfs.application.yfseventsserver.repository.StagingEmailDataRepository;
import com.yfs.application.yfseventsserver.repository.VolunteerRepository;
import com.yfs.application.yfseventsserver.repository.VolunteersAcceptedRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import javax.transaction.Transactional;
import java.util.*;
import java.util.stream.Stream;

@Service
public class StagingEmailPoller {

    @Value("${email.maxRetryCount}")
    private int maxRetryCountAllowed;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    StagingEmailDataRepository stagingEmailDataRepository;

    @Autowired
    VolunteersAcceptedRepository volunteersAcceptedRepository;

    @Autowired
    EmailController emailController;

    @Autowired
    EventDataRepository eventDataRepository;

    @Autowired
    EventController eventController;

    private static Logger logger = LoggerFactory.getLogger(StagingEmailPoller.class);

    @Scheduled(fixedDelay = 1000 * 30, initialDelay = 1000*1)
    public void processStagingEmail() {

        //TODO: If this the result set it too huge then leverage pagination
        List<StagingEmail> stagingEmails = stagingEmailDataRepository.getStagingEmailsEligibleToProcess(maxRetryCountAllowed);

        if(CollectionUtils.isEmpty(stagingEmails)){
            logger.info("No pending staging mail to be processed...");
            return;
        }
        System.out.println(stagingEmails);
        stagingEmails.parallelStream().forEach(i -> processEmail(i));
    }


    @Transactional
    public Event getEvent(long id)
    {
        Event event= eventDataRepository.findEventById(id).get();
        System.out.println("event details are"+event.getNgoName().get(0));
        return event;
    }

    @Transactional
    private void processEmail(StagingEmail stagingEmail) {
        final List<Email> filedEmailList = new ArrayList<>();
        if (null == stagingEmail) {
            logger.info("stagingEmail is null hence skipping");
        }
        int retryCount = stagingEmail.getRetryCount()+1;
        try {
            if (stagingEmail.getStatus().equals(EmailStatus.NOT_STARTED) && null != stagingEmail.getPayload() && !StringUtils.isEmpty(stagingEmail.getPayload())) {
                Email email = objectMapper.readValue(stagingEmail.getPayload(), Email.class);
                String[] toList = email.getTo().split(",");
                System.out.println("to list "+toList);
                Stream.of(toList).forEach(emailId -> {
                    VolunteersAccepted volunteersAccepted = new VolunteersAccepted(stagingEmail.getEventId(), emailId, false, VolunteersAccepted.EmailNotificationStatus.NOT_SENT);
                    logger.info("About to send email to [{}] with subject[{}]", emailId,email.getSubject());
//                    System.out.println("Entered here");
//                    Optional<Event> opEvent= eventDataRepository.findById(email.getEventId());
//                    System.out.println("Event is :"+opEvent.get().getNgoName());
//                     Event event = new Event();
//                     event.setCollege("ac");
//                     event.setComments("com");
//                     event.setEventAction("yes");
//                     event.setEventCategory("evCat");
//                     List list = new LinkedList();
//                     list.add("tushaar");
//                     event.setNgoName(list);

                    Event event= eventController.getEventById(email.getEventId());
                    boolean isEmailSent = EmailController.sendMailController(emailId,email.getCc(),email.getBcc(),email.getSubject(),email.getEventId(),event,email.getSubject());
                    if (isEmailSent) {
                        volunteersAccepted.setStatus(VolunteersAccepted.EmailNotificationStatus.SENT);
                        volunteersAcceptedRepository.save(volunteersAccepted);
                    }else{
                        volunteersAccepted.setStatus(VolunteersAccepted.EmailNotificationStatus.NOT_SENT);
                        volunteersAcceptedRepository.save(volunteersAccepted);
                        filedEmailList.add(email);
                    }
                });
                stagingEmail.setStatus(EmailStatus.IN_PROGRESS);
                stagingEmailDataRepository.save(stagingEmail);

            } else {
               // List<VolunteersAccepted> volunteersAcceptedList = getVolunteersAcceptedList();
                List<VolunteersAccepted> volunteersAcceptedList = volunteersAcceptedRepository.getAllByEmailNotSent();
                Email email = objectMapper.readValue(stagingEmail.getPayload(), Email.class);
                volunteersAcceptedList.stream().forEach(volunteersAccepted -> {
                    String emailId = volunteersAccepted.getMailId();
                    logger.info("processEmail Retry[{}]:: About to send email to[{}] with subject[{}]", retryCount, emailId,email.getSubject());

                    Event event= eventController.getEventById(email.getEventId());
                    boolean isEmailSent = emailController.sendMailController(emailId,email.getCc(),email.getBcc(),email.getSubject(),email.getEventId(),event,email.getSubject());
                    if (isEmailSent) {
                        volunteersAccepted.setStatus(VolunteersAccepted.EmailNotificationStatus.SENT);
                        volunteersAcceptedRepository.save(volunteersAccepted);
                    }else{
                        filedEmailList.add(email);

                        if(retryCount == maxRetryCountAllowed){
                            volunteersAccepted.setStatus(VolunteersAccepted.EmailNotificationStatus.FAILED);
                            volunteersAcceptedRepository.save(volunteersAccepted);
                        }
                    }
                });
            }
            stagingEmail.setRetryCount(retryCount);
            if(CollectionUtils.isEmpty(filedEmailList)){
                stagingEmail.setStatus(EmailStatus.COMPLETED);
            }else if(retryCount == maxRetryCountAllowed){
                stagingEmail.setStatus(EmailStatus.FAILED);
            }
            stagingEmailDataRepository.save(stagingEmail);
        }catch(Exception e){
            logger.error("Failed to process stagingEmail[{}] with exception[{}]",stagingEmail,e);
        }
    }
}

