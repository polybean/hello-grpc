package com.example.grpc;

import com.example.grpc.protos.GreetServiceGrpc;
import com.example.grpc.protos.Greeting;
import io.grpc.*;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.apache.commons.cli.*;


import javax.net.ssl.SSLException;
import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ClientApplication {
    private static final Logger logger = Logger.getLogger(ClientApplication.class.getName());

    private static String host;
    private static String port;
    private static String caFilePath;

    private final ManagedChannel channel;
    private final GreetServiceGrpc.GreetServiceBlockingStub blockingStub;

    private ClientApplication(String host, int port, SslContext sslContext) {
        this(NettyChannelBuilder.forAddress(host, port)
                .overrideAuthority("localhost")  /* Only for using provided test certs. */
                .sslContext(sslContext)
                .build());
    }

    private ClientApplication(ManagedChannel channel) {
        this.channel = channel;
        blockingStub = GreetServiceGrpc.newBlockingStub(channel).withCallCredentials(new CallCredentials() {

            @Override
            public void applyRequestMetadata(RequestInfo requestInfo, Executor executor, MetadataApplier metadataApplier) {
                try {
                    Metadata metadata = new Metadata();
                    metadata.put(Metadata.Key.of("token", Metadata.ASCII_STRING_MARSHALLER), "valid-token");
                    metadataApplier.apply(metadata);
                } catch (Throwable ex) {
                    metadataApplier.fail(Status.UNAUTHENTICATED.withCause(ex));
                }
            }

            @Override
            public void thisUsesUnstableApi() {

            }
        });
    }

    private static SslContext buildSslContext(String trustCertCollectionFilePath,
                                              String clientCertChainFilePath,
                                              String clientPrivateKeyFilePath) throws SSLException {
        SslContextBuilder builder = GrpcSslContexts.forClient();

        if (trustCertCollectionFilePath != null) {
            builder.trustManager(new File(trustCertCollectionFilePath));
        }

        if (clientCertChainFilePath != null && clientPrivateKeyFilePath != null) {
            builder.keyManager(new File(clientCertChainFilePath), new File(clientPrivateKeyFilePath));
        }

        return builder.build();
    }

    private void shutdown() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    private void greet(String firstName, String lastName) {
        Greeting.Greeter greeter = Greeting.Greeter.newBuilder().setFirstName(firstName).setLastName(lastName).build();
        logger.info("Will try to greet " + greeter + " ...");

        Greeting.GreetRequest request = Greeting.GreetRequest.newBuilder().setGreeter(greeter).build();
        Greeting.GreetResponse response;

        try {
            response = blockingStub.greet(request);

        } catch (StatusRuntimeException e) {
            logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
            return;
        }

        logger.info("Response from server: " + response.getResult());
    }

    private static void parseCommandLine(String[] args) {
        Options options = new Options();

        Option option = new Option(null, "host", true, "server hostname or ip");
        option.setRequired(false);
        options.addOption(option);

        option = new Option(null, "port", true, "server port");
        option.setRequired(true);
        options.addOption(option);

        option = new Option(null, "cacert", true, "trusted CA certificate");
        option.setRequired(true);
        options.addOption(option);

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        CommandLine cmd;

        try {
            cmd = parser.parse(options, args);

            host = cmd.getOptionValue("host", "localhost");
            port = cmd.getOptionValue("port");
            caFilePath = cmd.getOptionValue("cacert", "");
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            formatter.printHelp("utility-name", options);

            System.exit(1);
        }
    }

    public static void main(String[] args) throws Exception {
        parseCommandLine(args);

        ClientApplication client = new ClientApplication(
                host,
                Integer.parseInt(port),
                buildSslContext(caFilePath, null, null));

        try {
            client.greet("John", "Doe");
        } finally {
            client.shutdown();
        }
    }
}
