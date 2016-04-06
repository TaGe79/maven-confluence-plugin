package org.bsc.maven.plugin.confluence;

import org.apache.commons.io.IOUtils;
import org.bsc.markdown.ToConfluenceSerializer;
import org.junit.Test;
import org.pegdown.PegDownProcessor;
import org.pegdown.ast.AnchorLinkNode;
import org.pegdown.ast.ExpLinkNode;
import org.pegdown.ast.Node;
import org.pegdown.ast.RefLinkNode;
import org.pegdown.ast.RootNode;
import org.pegdown.ast.StrongEmphSuperNode;
import org.pegdown.ast.VerbatimNode;
import org.pegdown.ast.Visitor;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

/*
    public static final int NONE = 0;
    public static final int SMARTS = 1;
    public static final int QUOTES = 2;
    public static final int SMARTYPANTS = 3;
    public static final int ABBREVIATIONS = 4;
    public static final int HARDWRAPS = 8;
    public static final int AUTOLINKS = 16;
    public static final int TABLES = 32;
    public static final int DEFINITIONS = 64;
    public static final int FENCED_CODE_BLOCKS = 128;
    public static final int WIKILINKS = 256;
    public static final int STRIKETHROUGH = 512;
    public static final int ANCHORLINKS = 768;
    public static final int ALL = 65535;
    public static final int SUPPRESS_HTML_BLOCKS = 65536;
    public static final int SUPPRESS_INLINE_HTML = 131072;
    public static final int SUPPRESS_ALL_HTML = 196608;
*/
/**
 *
 * @author softphone
 */
public class PegdownTest {

    interface F<P extends Node> {
        void f( P node );
    }

    private static final String FILE0 = "TEST1.md";
    private static final String FILE = "getting_started.md";

    private char[] loadResource( String name ) throws IOException {

        final ClassLoader cl = PegdownTest.class.getClassLoader();

        final java.io.InputStream is = cl.getResourceAsStream(name);
        try {

            java.io.CharArrayWriter caw = new java.io.CharArrayWriter();

            for( int c = is.read(); c!=-1; c = is.read() ) {
                caw.write( c );
            }

            return caw.toCharArray();

        }
        finally {
            IOUtils.closeQuietly(is);
        }

    }

    static class IfContext {

        static final IfContext IsTrue = new IfContext(true);
        static final IfContext IsFalse = new IfContext(false);

        final boolean condition ;

        public IfContext(boolean condition) {
            this.condition = condition;
        }


        <T extends Node> IfContext elseIf( Object n, Class<T> clazz, F<T> cb ) {
            return ( condition ) ? IsTrue : iF( n, clazz, cb );
        }

        static <T extends Node> IfContext iF( Object n, Class<T> clazz, F<T> cb ) {

            if( clazz.isInstance(n)) {

                cb.f( clazz.cast(n));
                return IsTrue;
            }
            return IsFalse;
        }

    }

    final F<StrongEmphSuperNode> sesn = new F<StrongEmphSuperNode>() {

        @Override
        public void f(StrongEmphSuperNode node) {
           System.out.printf( " chars=[%s], strong=%b, closed=%b", node.getChars(), node.isStrong(), node.isClosed() );

        }

    };

    final F<ExpLinkNode> eln = new F<ExpLinkNode>() {

        @Override
        public void f(ExpLinkNode node) {
           System.out.printf( " title=[%s], url=[%s]", node.title, node.url );

        }

    };
    final F<AnchorLinkNode> aln = new F<AnchorLinkNode>() {

        @Override
        public void f(AnchorLinkNode node) {
           System.out.printf( " name=[%s], text=[%s]", node.getName(), node.getText());

        }

    };
    final F<VerbatimNode> vln = new F<VerbatimNode>() {

        @Override
        public void f(VerbatimNode node) {
           System.out.printf( " text=[%s], type=[%s]", node.getText(), node.getType());

        }

    };
    final F<RefLinkNode> rln = new F<RefLinkNode>() {

        @Override
        public void f(RefLinkNode node) {
           System.out.printf( " separatorSpace=[%s]", node.separatorSpace);

        }

    };

    @Test
    public void parseTest() throws IOException {

        InvocationHandler handler = new InvocationHandler() {

            int indent = 0;

            protected void visitChildren(Object proxy, Node node ) {
                    for (Node child : node.getChildren()) {
                        child.accept((Visitor) proxy);
                    }
            }
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

                for( int i = 0; i <indent ; ++i ) System.out.print('\t');
                final Object n = args[0];

                System.out.printf( "[%s]", n );
                IfContext.iF(n, StrongEmphSuperNode.class, sesn)
                            .elseIf(n, ExpLinkNode.class, eln)
                            .elseIf(n, AnchorLinkNode.class, aln)
                            .elseIf(n, VerbatimNode.class, vln)
                            .elseIf(n, RefLinkNode.class, rln)

                        ;
                System.out.println();

                if( n instanceof Node ) {
                    ++indent;
                    visitChildren(proxy, (Node)args[0]);
                    --indent;
                }
                return null;
            }

        };

        final ClassLoader cl = PegdownTest.class.getClassLoader();

        final Visitor proxy = (Visitor) Proxy.newProxyInstance(
                            cl,
                            new Class[] { Visitor.class },
                            handler);


        final PegDownProcessor p = new PegDownProcessor(ToConfluenceSerializer.extensions() );


        final RootNode root = p.parseMarkdown(loadResource(FILE));

        root.accept(proxy);
    }

    @Test
    public void serializerTest() throws IOException {

        final PegDownProcessor p = new PegDownProcessor(ToConfluenceSerializer.extensions());

        final RootNode root = p.parseMarkdown(loadResource(FILE0));

        ToConfluenceSerializer ser =  new ToConfluenceSerializer() {

            @Override
            protected void notImplementedYet(Node node) {
                throw new UnsupportedOperationException( String.format("Node [%s] not supported yet. ", node.getClass().getSimpleName()) );
            }

        };

        root.accept( ser );

        System.out.println( ser.toString() );

    }
}
