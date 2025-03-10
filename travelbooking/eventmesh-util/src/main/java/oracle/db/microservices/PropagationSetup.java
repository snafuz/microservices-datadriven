/*

 **
 ** Copyright (c) 2021 Oracle and/or its affiliates.
 ** Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl/
 */
package oracle.db.microservices;

import oracle.AQ.*;
import oracle.jms.*;

import javax.jms.*;
import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static oracle.db.microservices.ATPAQAdminResource.*;

class PropagationSetup {
    String GET_OBJECT_CWALLETSSO_DATA_PUMP_DIR = "BEGIN " +
            "DBMS_CLOUD.GET_OBJECT(" +
            "object_uri => ?, " +
            "directory_name => ?); " +
            "END;";

    String DROP_CREDENTIAL_SQL = "BEGIN " +
            "DBMS_CLOUD.DROP_CREDENTIAL(" +
            "credential_name => ?" +
            ");" +
            "END;";

    String CREATE_CREDENTIAL_SQL = " BEGIN" +
            "  DBMS_CLOUD.CREATE_CREDENTIAL(" +
            "  credential_name => ?," +
            "  username => ?," +
            "  password => ?" +
            "  );" +
            " END;";

    String CREATE_DBLINK_SQL = "BEGIN " +
            "DBMS_CLOUD_ADMIN.CREATE_DATABASE_LINK(" +
            "db_link_name => ?," +
            "hostname => ?," +
            "port => ?," +
            "service_name => ?," +
            "ssl_server_cert_dn => ?," +
            "credential_name => ?," +
            "directory_name => ?);" +
            "END;";

    String DATA_PUMP_DIR = "DATA_PUMP_DIR";

    String createInventoryTable(DataSource participantpdbDataSource) throws SQLException {
        System.out.println("createInventoryTable and add items");
        String returnValue = "createInventoryTable and add items... ";
        try (Connection connection = participantpdbDataSource.getConnection(participantuser, participantpw)) {
            connection.createStatement().execute("drop table participant");
        } catch (SQLException ex) {
            System.out.println("PropagationSetup.createInventoryTable participant table was not dropped. This is expected during initial setup as it doesn't exist yet.");
        }
        try (Connection connection = participantpdbDataSource.getConnection(participantuser, participantpw)) {
            returnValue += " participant table dropped (if it existed), about to create participant table...";
            connection.createStatement().execute(
                    "create table participant (participantid varchar(16) PRIMARY KEY NOT NULL, participantlocation varchar(32), participantcount integer CONSTRAINT positive_participant CHECK (participantcount >= 0) )");
            returnValue += " participant table created, about to insert into participant...";
            connection.createStatement().execute("insert into participant values ('sushi', '1468 WEBSTER ST,San Francisco,CA', 0)");
            connection.createStatement().execute("insert into participant values ('pizza', '1469 WEBSTER ST,San Francisco,CA', 0)");
            connection.createStatement().execute("insert into participant values ('burger', '1470 WEBSTER ST,San Francisco,CA', 0)");
            returnValue += " table populated with food types, success";
        } catch (SQLException ex) {
            ex.printStackTrace();
            returnValue += ex.toString();
        }
        return returnValue;
    }

    String createUsers(DataSource travelagencypdbDataSource, DataSource participantpdbDataSource) {
        String returnValue = "";
        try {
            returnValue += createAQUser(travelagencypdbDataSource, travelagencyuser, travelagencypw);
        } catch (SQLException ex) {
            ex.printStackTrace();
            returnValue += ex.toString();
        }
        try {
            returnValue += createAQUser(participantpdbDataSource, participantuser, participantpw);
        } catch (SQLException ex) {
            ex.printStackTrace();
            returnValue += ex.toString();
        }
        return returnValue;
    }

    Object createAQUser(DataSource ds, String queueOwner, String queueOwnerPW) throws SQLException {
        String outputString = "createAQUser queueOwner = [" + queueOwner + "]";
        System.out.println("PropagationSetup.createAQUser queueOwner = [" + queueOwner + "]");
        try (Connection connection = ds.getConnection()) {
            connection.prepareStatement("CREATE OR REPLACE PROCEDURE CREATEDATAAPPUSER (userNam NVARCHAR2, userPw NVARCHAR2)" +
                    " IS" +
                    " v_sql  VARCHAR2 (32767);" +
                    " BEGIN" +
                    " v_sql := 'CREATE USER \"' || userNam || '\" IDENTIFIED BY \"' || userPw || '\"';" +
                    " EXECUTE IMMEDIATE v_sql;" +
                    " END CREATEDATAAPPUSER;" ).execute();
            CallableStatement pstmt = connection.prepareCall("{call CREATEDATAAPPUSER(?,?)}");
            pstmt.setNString(1, queueOwner.trim());
            pstmt.setNString(2, queueOwnerPW.trim());
            pstmt.execute();
            connection.createStatement().execute("GRANT pdb_dba TO " + queueOwner);
            connection.createStatement().execute("GRANT EXECUTE ON DBMS_CLOUD_ADMIN TO " + queueOwner);
            connection.createStatement().execute("GRANT EXECUTE ON DBMS_CLOUD TO " + queueOwner);
            connection.createStatement().execute("GRANT CREATE DATABASE LINK TO " + queueOwner);
            connection.createStatement().execute("GRANT unlimited tablespace to " + queueOwner);
            connection.createStatement().execute("GRANT connect, resource TO " + queueOwner);
            connection.createStatement().execute("GRANT aq_user_role TO " + queueOwner);
            connection.createStatement().execute("GRANT EXECUTE ON sys.dbms_aqadm TO " + queueOwner);
            connection.createStatement().execute("GRANT EXECUTE ON sys.dbms_aq TO " + queueOwner);
        }
        return outputString + " successful";
    }

    String createDBLinks(DataSource travelagencypdbDataSource, DataSource participantpdbDataSource) throws SQLException {
        System.out.println("createDBLinks...");
        try (Connection connection = travelagencypdbDataSource.getConnection(travelagencyuser, travelagencypw)) {
            // create link from travelagency to participant...
            createDBLink(connection,"INVENTORYPDB_CRED", "travelagencytoparticipant", travelagencyToInventoryLinkName);
        }
        try (Connection connection = participantpdbDataSource.getConnection(participantuser, participantpw)) {
            createDBLink(connection, "ORDERPDB_CRED", "participanttotravelagency", participantToOrderLinkName);
        }
        verifyDBLinks(travelagencypdbDataSource, participantpdbDataSource);
        return "DBLinks created and verified successfully";
    }

    private void createDBLink(Connection connection, String credName, String createlink, String linkname) throws SQLException {
        boolean isOrderToInventory = createlink.equals("travelagencytoparticipant"); // if it's not OrderToInventory it's InventoryToOrder
        System.out.println(" creating link:" + linkname);
        PreparedStatement preparedStatement2 = connection.prepareStatement(GET_OBJECT_CWALLETSSO_DATA_PUMP_DIR);
        preparedStatement2.setString(1, cwalletobjecturi);
        preparedStatement2.setString(2, DATA_PUMP_DIR);
        preparedStatement2.execute();
        try {
            System.out.println("About to drop credential (if exists) " + credName);
            PreparedStatement preparedStatement = connection.prepareStatement(DROP_CREDENTIAL_SQL);
            preparedStatement.setString(1, credName);
            preparedStatement.execute();
        } catch (SQLException ex) {
            System.out.println("Drop credential failed as expected (if this is initial setup)");
        }
        System.out.println("GET_OBJECT cwalletobjecturi successful, about to create credential");
        PreparedStatement preparedStatement1 = connection.prepareStatement(CREATE_CREDENTIAL_SQL);
        preparedStatement1.setString(1, credName);
        preparedStatement1.setString(2, isOrderToInventory?participantuser:travelagencyuser);
        preparedStatement1.setString(3, isOrderToInventory?participantpw.trim():travelagencypw.trim());
        preparedStatement1.execute();
        System.out.println("CREATE_CREDENTIAL successful, about to create link" + createlink);
        PreparedStatement preparedStatement = connection.prepareStatement(CREATE_DBLINK_SQL);
        preparedStatement.setString(1, isOrderToInventory ? travelagencyToInventoryLinkName : participantToOrderLinkName);
        preparedStatement.setString(2, isOrderToInventory ? participanthostname : travelagencyhostname);
        preparedStatement.setInt(3, Integer.valueOf(isOrderToInventory ? participantport : travelagencyport));
        preparedStatement.setString(4, isOrderToInventory ? participantservice_name : travelagencyservice_name);
        preparedStatement.setString(5, isOrderToInventory ? participantssl_server_cert_dn : travelagencyssl_server_cert_dn);
        preparedStatement.setString(6, credName);
        preparedStatement.setString(7, DATA_PUMP_DIR);
        preparedStatement.execute();
        System.out.println(" CREATE_DATABASE_LINK " + linkname + " successful,");
    }

    String verifyDBLinks(DataSource travelagencypdbDataSource, DataSource participantpdbDataSource) throws SQLException {
        String returnString = "";
        returnString += "travelagencyuser select on participantpdb using link...";
        try (Connection travelagencyconnection = travelagencypdbDataSource.getConnection(travelagencyuser, travelagencypw)) {
            System.out.println("verifyDBLinks travelagencyconnection:" + travelagencyconnection);
            travelagencyconnection.createStatement().execute("create table templinktest (id varchar(32))");
            System.out.println("verifyDBLinks temp table created on travelagency");
            // verify travelagencyuser select on participantpdb using link...
            travelagencyconnection.createStatement().execute("select count(*) from participantuser.templinktest@" + travelagencyToInventoryLinkName);
            System.out.println("verifyDBLinks select on participantuser.templinktest");
            travelagencyconnection.createStatement().execute("drop table templinktest");
            returnString += "success";
        } catch (SQLException ex) {
            returnString += ex;
        }
        returnString += "participantuser select on travelagencypdb using link...";
        try (Connection participantconnection = participantpdbDataSource.getConnection(participantuser, participantpw)) {
            System.out.println("verifyDBLinks participantconnection:" + participantconnection);
            participantconnection.createStatement().execute("create table templinktest (id varchar(32))");
            System.out.println("verifyDBLinks temp table created on participant");
            // verify participantuser select on travelagencypdb using link  ...
            participantconnection.createStatement().execute("select count(*) from travelagencyuser.templinktest@" + participantToOrderLinkName);
            System.out.println("verifyDBLinks select on travelagencyuser.templinktest");
            participantconnection.createStatement().execute("drop table templinktest");
            returnString += "success";
        } catch (SQLException ex) {
            returnString += ex;
        }
        return returnString;
    }

    public String setupTablesQueuesAndPropagation(DataSource travelagencypdbDataSource, DataSource participantpdbDataSource,
                                                  boolean isSetupOrderToInventory, boolean isSetupInventoryToOrder) {
        System.out.println("PropagationSetup.setup isSetupOrderToInventory:" + isSetupOrderToInventory +
                " isSetupInventoryToOrder:" + isSetupInventoryToOrder);
        String returnString = "";
        //propagation of travelagency queue from travelagencypdb to participantpdb
        if (isSetupOrderToInventory)
            returnString += setupTablesQueuesAndPropagation(travelagencypdbDataSource, participantpdbDataSource,
                    travelagencyuser, travelagencypw, travelagencyQueueName,
                    travelagencyQueueTableName, participantuser, participantpw,
                    travelagencyToInventoryLinkName, false);
        //propagation of participant queue from participantpdb to travelagencypdb
        if (isSetupInventoryToOrder)
            returnString += setupTablesQueuesAndPropagation(participantpdbDataSource, travelagencypdbDataSource,
                    participantuser, participantpw, participantQueueName,
                    participantQueueTableName, travelagencyuser, travelagencypw,
                    participantToOrderLinkName, false);
        return returnString;
    }

    public String testOrderToInventory(DataSource travelagencypdbDataSource, DataSource participantpdbDataSource) {
        String returnString = "in testOrderToInventory...";
        //propagation of travelagency queue from travelagencypdb to participantpdb
        returnString += setupTablesQueuesAndPropagation(travelagencypdbDataSource, participantpdbDataSource,
                travelagencyuser, travelagencypw, travelagencyQueueName,
                travelagencyQueueTableName, participantuser, participantpw,
                travelagencyToInventoryLinkName, true);
        return returnString;
    }

    public String testInventoryToOrder(DataSource travelagencypdbDataSource, DataSource participantpdbDataSource) {
        String returnString = "in testInventoryToOrder...";
        //propagation of participant queue from participantpdb to travelagencypdb
        returnString += setupTablesQueuesAndPropagation(participantpdbDataSource, travelagencypdbDataSource,
                participantuser, participantpw, participantQueueName,
                participantQueueTableName, travelagencyuser, travelagencypw,
                participantToOrderLinkName, true);
        return returnString;
    }

    private String setupTablesQueuesAndPropagation(
            DataSource sourcepdbDataSource, DataSource targetpdbDataSource, String sourcename, String sourcepw,
            String sourcequeuename, String sourcequeuetable, String targetuser, String targetpw,
            String linkName, boolean isTest) {
        String returnString = "sourcepdbDataSource = [" + sourcepdbDataSource + "], " +
                "targetpdbDataSource = [" + targetpdbDataSource + "], " +
                "sourcename = [" + sourcename + "], sourcepw = [" + sourcepw + "], " +
                "sourcequeuename = [" + sourcequeuename + "], " +
                "sourcequeuetable = [" + sourcequeuetable + "], targetuser = [" + targetuser + "], " +
                "targetpw = [" + targetpw + "], linkName = [" + linkName + "] isTest = " + isTest;
        System.out.println(returnString);
        try {
            TopicConnectionFactory tcfact = AQjmsFactory.getTopicConnectionFactory(sourcepdbDataSource);
            TopicConnection tconn = tcfact.createTopicConnection(sourcename, sourcepw);
            QueueConnectionFactory qcfact = AQjmsFactory.getQueueConnectionFactory(targetpdbDataSource);
            QueueConnection qconn = qcfact.createQueueConnection(targetuser, targetpw);
            TopicSession tsess = tconn.createTopicSession(true, Session.CLIENT_ACKNOWLEDGE);
            System.out.println("setup source topicsession:" + tsess);
            QueueSession qsess = qconn.createQueueSession(true, Session.CLIENT_ACKNOWLEDGE);
            System.out.println("setup destination queuesession:" + qsess);
            tconn.start();
            qconn.start();
            if (!isTest) setupTopicAndQueue(tsess, qsess, sourcename, targetuser, sourcequeuename, sourcequeuetable);
            performJmsOperations(tsess, qsess, sourcename, targetuser, sourcequeuename, linkName, isTest);
            tsess.close();
            tconn.close();
            qsess.close();
            qconn.close();
            System.out.println("success for " + returnString);
            return "success for " + sourcequeuename;
        } catch (Exception ex) {
            System.out.println("Exception-1: " + ex);
            ex.printStackTrace();
            return "setup failed:" + ex.toString() + returnString;
        }
    }

    public static void setupTopicAndQueue(
            TopicSession topicSession, QueueSession queueSession,
            String topicuser, String queueuser,
            String name,
            String tableName) throws Exception {
        try {
            System.out.println("drop source queue table if it exists...");
            try {
                AQQueueTable qtable = ((AQjmsSession) topicSession).getQueueTable(topicuser, tableName);
                qtable.drop(true);
            } catch (Exception e) {
                System.out.println("Exception in dropping source (expected if it does not exist)" + e);
            }
            System.out.println("drop destination queue table if it exists...");
            try {
                AQQueueTable qtable = ((AQjmsSession) queueSession).getQueueTable(queueuser, tableName);
                qtable.drop(true);
            } catch (Exception e) {
                System.out.println("Exception in dropping destination (expected if it does not exist)" + e);
            }
            System.out.println("Creating Input Topic Table...");
            AQQueueTableProperty aqQueueTableProperty = new AQQueueTableProperty("SYS.AQ$_JMS_TEXT_MESSAGE");
            aqQueueTableProperty.setComment("input topic");
            aqQueueTableProperty.setMultiConsumer(true);
            aqQueueTableProperty.setCompatible("8.1");
            aqQueueTableProperty.setPayloadType("SYS.AQ$_JMS_TEXT_MESSAGE");
            AQQueueTable inputTopicTable = ((AQjmsSession) topicSession).createQueueTable(topicuser, tableName, aqQueueTableProperty);

            System.out.println("Creating Propagation Queue Table...");
            AQQueueTableProperty qtprop2 = new AQQueueTableProperty("SYS.AQ$_JMS_TEXT_MESSAGE");
            qtprop2.setComment("propagation queue");
            qtprop2.setPayloadType("SYS.AQ$_JMS_TEXT_MESSAGE");
            qtprop2.setMultiConsumer(false);
            qtprop2.setCompatible("8.1");
            AQQueueTable propagationQueueTable = ((AQjmsSession) queueSession).createQueueTable(queueuser, tableName, qtprop2);

            System.out.println("Creating Topic input_queue...");
            AQjmsDestinationProperty aqjmsDestinationProperty = new AQjmsDestinationProperty();
            aqjmsDestinationProperty.setComment("create topic ");
            Topic topic1 = ((AQjmsSession) topicSession).createTopic(inputTopicTable, name, aqjmsDestinationProperty);

            System.out.println("Creating queue prop_queue...");
            aqjmsDestinationProperty.setComment("create queue");
            Queue queue1 = ((AQjmsSession) queueSession).createQueue(propagationQueueTable, name, aqjmsDestinationProperty);

            ((AQjmsDestination) topic1).start(topicSession, true, true);
            ((AQjmsDestination) queue1).start(queueSession, true, true);
            System.out.println("Successfully setup topic and queue");
        } catch (Exception ex) {
            System.out.println("Error in setupTopic: " + ex);
            throw ex;
        }
    }


    public static void performJmsOperations(
            TopicSession topicSession, QueueSession queueSession,
            String sourcetopicuser, String destinationqueueuser,
            String name,
            String linkName, boolean isTest)
            throws Exception {
        try {
            System.out.println("Setup topic/source and queue/destination for propagation... isTest:" + isTest);
            Topic topic1 = ((AQjmsSession) topicSession).getTopic(sourcetopicuser, name);
            Queue queue = ((AQjmsSession) queueSession).getQueue(destinationqueueuser, name);
            System.out.println("Creating Topic Subscribers... queue:" + queue.getQueueName());
            AQjmsConsumer[] subs = new AQjmsConsumer[1];
            subs[0] = (AQjmsConsumer) queueSession.createConsumer(queue);
            if (!isTest)
                createRemoteSubAndSchedulePropagation(topicSession, destinationqueueuser, name, linkName, topic1, queue);
            sendMessages(topicSession, topic1);
            Thread.sleep(50000);
            receiveMessages(queueSession, subs);
        } catch (Exception e) {
            System.out.println("Error in performJmsOperations: " + e);
            throw e;
        }
    }

    private static void createRemoteSubAndSchedulePropagation(TopicSession topicSession, String destinationqueueuser, String name, String linkName, Topic topic1, Queue queue) throws JMSException, SQLException {
        System.out.println("_____________________________________________");
        System.out.println("performJmsOperations queue.getQueueName():" + queue.getQueueName());
        System.out.println("performJmsOperations name (prefixing " + destinationqueueuser + ". to this):" + name);
        System.out.println("_____________________________________________");
        AQjmsAgent agt = new AQjmsAgent("", destinationqueueuser + "." + name + "@" + linkName);
//        ((AQjmsSession) topicSession).createRemoteSubscriber(topic1, agt, "JMSPriority = 2");
        ((AQjmsSession) topicSession).createRemoteSubscriber(topic1, agt, null);
        ((AQjmsDestination) topic1).schedulePropagation(
                topicSession, linkName, null, null, null, new Double(0));
    }

    String enablePropagation(DataSource dataSource, String topicUser, String topicPassword, String topicName, String linkName) {
        System.out.println("schedulePropagation");
        TopicConnection tconn = null;
        try {
            tconn = AQjmsFactory.getTopicConnectionFactory(dataSource).createTopicConnection(
                    topicUser, topicPassword);
            TopicSession tsess = tconn.createTopicSession(true, Session.CLIENT_ACKNOWLEDGE);
            Topic topic1 = ((AQjmsSession) tsess).getTopic(topicUser, topicName);
            ((AQjmsDestination) topic1).enablePropagationSchedule(tsess, linkName);
        } catch (JMSException e) {
            e.printStackTrace();
            return e.toString();
        }
        return topicName + " : success ";
    }

    private static void sendMessages(TopicSession topicSession, Topic topic) throws JMSException {
        System.out.println("Publish test messages...");
        TextMessage objmsg = topicSession.createTextMessage();
        TopicPublisher publisher = topicSession.createPublisher(topic);
        objmsg.setIntProperty("Id", 1);
        objmsg.setStringProperty("City", "Philadelphia");
        objmsg.setText(1 + ":" + "message# " + 1 + ":" + 500);
        objmsg.setIntProperty("Priority", 2);
        objmsg.setJMSCorrelationID("" + 12);
        objmsg.setJMSPriority(2);
        publisher.publish(topic, objmsg, DeliveryMode.PERSISTENT, 2, AQjmsConstants.EXPIRATION_NEVER);
        publisher.publish(topic, objmsg, DeliveryMode.PERSISTENT, 3, AQjmsConstants.EXPIRATION_NEVER);
        System.out.println("Commit now and sleep...");
        topicSession.commit();
    }

    private static void receiveMessages(QueueSession qsess, AQjmsConsumer[] subs) throws JMSException {
        System.out.println("Receive test messages...");
        for (int i = 0; i < subs.length; i++) {
            System.out.println("Messages for subscriber : " + i);
            if (subs[i].getMessageSelector() != null) {
                System.out.println("  with selector: " + subs[i].getMessageSelector());
            }
            boolean done = false;
            while (!done) {
                try {
                    TextMessage robjmsg = (TextMessage) (subs[i].receiveNoWait());
                    if (robjmsg != null) {
                        String rTextMsg = robjmsg.getText();
                        System.out.println("rTextMsg " + rTextMsg);
                        System.out.print(" Pri: " + robjmsg.getJMSPriority());
                        System.out.print(" Message: " + robjmsg.getIntProperty("Id"));
                        System.out.print(" " + robjmsg.getStringProperty("City"));
                        System.out.println(" " + robjmsg.getIntProperty("Priority"));
                    } else {
                        System.out.println("No more messages.");
                        done = true;
                    }
                    qsess.commit();
                } catch (Exception e) {
                    System.out.println("Error in performJmsOperations: " + e);
                    done = true;
                }
            }
        }
    }

    String unschedulePropagation(DataSource dataSource, String topicUser,
                                 String topicPassword, String topicName, String linkName) {
        String resultString = "unschedulePropagation dataSource = [" + dataSource + "], " +
                "topicUser = [" + topicUser + "], " +
                "topicName = [" + topicName + "], linkName = [" + linkName + "]";
        System.out.println(resultString);
        TopicConnection tconn;
        try {
            tconn = AQjmsFactory.getTopicConnectionFactory(dataSource).createTopicConnection(
                    topicUser, topicPassword);
            TopicSession tsess = tconn.createTopicSession(true, Session.CLIENT_ACKNOWLEDGE);
            Topic topic1 = ((AQjmsSession) tsess).getTopic(topicUser, topicName);
            ((AQjmsDestination) topic1).unschedulePropagation(tsess, linkName);
            resultString += "success";
        } catch (JMSException e) {
            e.printStackTrace();
            resultString += e.toString();
        }
        return resultString;
    }

    String deleteUsers(DataSource travelagencypdbDataSource, DataSource participantpdbDataSource) throws SQLException {
        String returnValue = "";
        Connection connection = null;
        try {
            connection = travelagencypdbDataSource.getConnection();
            System.out.println("travelagencypdbDataSource connection:" + connection);
            connection.createStatement().execute("drop user ORDERUSER cascade ");
            returnValue += " ORDERUSER dropped successfully";
        } catch (SQLException ex) {
            ex.printStackTrace();
            returnValue += ex.toString();
        } finally {
            if (connection != null) connection.close();
        }
        try {
            connection = participantpdbDataSource.getConnection();
            System.out.println("participantpdbDataSource connection:" + connection);
            connection.createStatement().execute("drop user INVENTORYUSER cascade ");
            returnValue += " INVENTORYUSER dropped successfully";
        } catch (SQLException ex) {
            ex.printStackTrace();
            returnValue += ex.toString();
        } finally {
            if (connection != null) connection.close();
        }
        return returnValue;
    }
}

