/*
 * Copyright (c) 2002-2018 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.causalclustering.protocol.handshake;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.neo4j.causalclustering.core.CausalClusteringSettings;
import org.neo4j.causalclustering.messaging.SimpleNettyChannel;
import org.neo4j.causalclustering.protocol.NettyPipelineBuilderFactory;
import org.neo4j.causalclustering.protocol.Protocol;
import org.neo4j.causalclustering.protocol.ProtocolInstaller;
import org.neo4j.causalclustering.protocol.ProtocolInstallerRepository;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.logging.Log;
import org.neo4j.logging.LogProvider;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class HandshakeClientInitializer extends ChannelInitializer<SocketChannel>
{
    private final Log log;
    private final ProtocolRepository protocolRepository;
    private final Protocol.Identifier protocolName;
    private final Duration timeout;
    private final ProtocolInstallerRepository<ProtocolInstaller.Orientation.Client> protocolInstaller;
    private final NettyPipelineBuilderFactory pipelineBuilderFactory;

    public HandshakeClientInitializer( LogProvider logProvider, ProtocolRepository protocolRepository, Protocol.Identifier protocolName,
            ProtocolInstallerRepository<ProtocolInstaller.Orientation.Client> protocolInstallerRepository, Config config,
            NettyPipelineBuilderFactory pipelineBuilderFactory )
    {
        this.log = logProvider.getLog( getClass() );
        this.protocolRepository = protocolRepository;
        this.protocolName = protocolName;
        this.timeout = config.get( CausalClusteringSettings.handshake_timeout );
        this.protocolInstaller = protocolInstallerRepository;
        this.pipelineBuilderFactory = pipelineBuilderFactory;
    }

    private void installHandlers( Channel channel, HandshakeClient handshakeClient ) throws Exception
    {
        pipelineBuilderFactory.create( channel, log )
                .addFraming()
                .add( new ClientMessageEncoder() )
                .add( new ClientMessageDecoder() )
                .add( new NettyHandshakeClient( handshakeClient ) )
                .install();
    }

    @Override
    protected void initChannel( SocketChannel channel ) throws Exception
    {
        HandshakeClient handshakeClient = new HandshakeClient();
        installHandlers( channel, handshakeClient );

        scheduleHandshake( channel, handshakeClient, 0 );
        scheduleTimeout( channel, handshakeClient );
    }

    /**
     * schedules the handshake initiation after the connection attempt
     */
    private void scheduleHandshake( SocketChannel ch, HandshakeClient handshakeClient, long delay )
    {
        ch.eventLoop().schedule( () ->
        {
            if ( ch.isActive() )
            {
                initiateHandshake( ch, handshakeClient );
            }
            else if ( ch.isOpen() )
            {
                scheduleHandshake( ch, handshakeClient, delay + 1 );
            }
        }, delay, MILLISECONDS );
    }

    private void scheduleTimeout( SocketChannel ch, HandshakeClient handshakeClient )
    {
        ch.eventLoop().schedule( () -> handshakeClient.checkTimeout( timeout ), timeout.toMillis(), TimeUnit.MILLISECONDS );
    }

    private void initiateHandshake( Channel ch, HandshakeClient handshakeClient )
    {
        SimpleNettyChannel channelWrapper = new SimpleNettyChannel( ch, log );
        CompletableFuture<ProtocolStack> handshake = handshakeClient.initiate( channelWrapper, protocolRepository, protocolName );

        handshake.whenComplete( ( protocolStack, failure ) -> onHandshakeComplete( protocolStack, ch, failure ) );
    }

    private void onHandshakeComplete( ProtocolStack protocolStack, Channel channel, Throwable failure )
    {
        if ( failure != null )
        {
            log.error( "Error when negotiating protocol stack", failure );
            channel.pipeline().fireUserEventTriggered( HandshakeFinishedEvent.getFailure() );
        }
        else
        {
            try
            {
                protocolInstaller.installerFor( protocolStack.applicationProtocol() ).install( channel );
                channel.pipeline().fireUserEventTriggered( HandshakeFinishedEvent.getSuccess() );
            }
            catch ( Exception e )
            {
                // TODO: handle better?
                channel.close();
            }
        }
    }
}
