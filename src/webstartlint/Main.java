
package webstartlint;

import com.sun.webkit.dom.NodeListImpl;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * The goal is to make a system that does sanity checks on Webstart apps.  Webstart likes to assume 
 * clients are smart and can figure out what's wrong by looking at them, but we can't.
 * @author jbf
 */
public class Main {

    Logger logger= Logger.getLogger(Main.class.getSimpleName());
    
    /**
     * @param args the command line arguments
     * @throws java.net.MalformedURLException
     * @throws javax.xml.xpath.XPathExpressionException
     */
    public static void main(String[] args) throws MalformedURLException, IOException, XPathExpressionException {

        //args= new String[] { "http://autoplot.org:8080/hudson/job/autoplot-release/lastSuccessfulBuild/artifact/autoplot/VirboAutoplot/dist/autoplot.jnlp" };
        //args= new String[] { "http://autoplot.org/jnlp.cgi" };

        if ( args.length!=1 ) {
            System.err.println( "java -jar WebStartLint.jar webstartlint.Main <javaws>");
            System.exit(1);
        }
        if ( new Main().check( args[0], true ) ) {
            System.exit( 0 );
        } else {
            System.exit( 1 );
        }
    }

    private Document insToDocument( InputStream ins ) throws MalformedURLException, IOException {
        InputSource source = new InputSource( ins );
        try {
            DocumentBuilder builder;
            builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc;
            doc = builder.parse(source);
            return doc;
        } catch ( IOException ex ) {
            throw new RuntimeException(ex);
        } catch (ParserConfigurationException ex) {
            throw new RuntimeException(ex);
        } catch (SAXException ex) {
            throw new RuntimeException(ex);
        }
        
    }
    
    private Document urlToDocument( String  surl ) throws MalformedURLException, IOException {
        URL url = new URL( surl);
        URLConnection urlc;
        urlc = url.openConnection();
        urlc.setConnectTimeout(300);

        InputStream ins= urlc.getInputStream();
        return insToDocument(ins);
        
    }

    /**
     * copies data from in to out, sending the number of bytesTransferred to the monitor.
     * @param is
     * @param out
     * @throws java.io.IOException
     */
    protected void copyStream(InputStream is, OutputStream out ) throws IOException {
        byte[] buffer = new byte[2048];
        int bytesRead = is.read(buffer, 0, 2048);
        long totalBytesRead = bytesRead;
        while (bytesRead > -1) {
            out.write(buffer, 0, bytesRead);
            bytesRead = is.read(buffer, 0, 2048);
            totalBytesRead += bytesRead;
        }
    }

    private boolean download( URL url, File partFile ) throws IOException {
        URLConnection urlc= url.openConnection();
        if ( partFile.exists() || partFile.createNewFile()) { // exists because Java creates a 0 byte file
            InputStream in;
            in = urlc.getInputStream();

            FileOutputStream out = new FileOutputStream(partFile);
            try {
                copyStream(in, out );
                out.close();
                in.close();
                return true;
            } catch (IOException e) {
                out.close();
                in.close();
                partFile.delete();
                throw e;
            }
        } else {
            throw new IOException("couldn't create local file: " + partFile);
        }
    }

    void readSignatures( Map<String,String> sigs ) {

    }
    
    private NodeList getKidsSansCommentsText( Node parent ) {
        NodeList nn= parent.getChildNodes();
        
        List<Node> rm= new ArrayList<>();
        
        for ( int i=0; i<nn.getLength(); i++ ) {
            if ( nn.item(i).getNodeType()==Node.TEXT_NODE ) {
                rm.add(nn.item(i));
            } else if ( nn.item(i).getNodeType()==Node.COMMENT_NODE ) {
                rm.add(nn.item(i));
            }
        } 
        for ( Node n: rm ) {
            parent.removeChild(n);
        }
        //NodeList nn2=parent.getChildNodes();
        //for ( int i=0; i<nn2.getLength(); i++ ) {
        //    System.err.println( nn2.item(i).getNodeType() + " "+ nn2.item(i).getNodeName() + " "+ nn2.item(i).getNodeValue() );
        //}
        return parent.getChildNodes();
    }
    
    private String checkTemplate( Node template, Node check ) {
        NamedNodeMap attr= check.getAttributes();
        if ( attr!=null ) {
            for ( int i=0; i<attr.getLength(); i++ ) {
                Node n= attr.item(i);
                Node t= template.getAttributes().getNamedItem(n.getNodeName());
                if ( t==null ) {
                    logger.log(Level.FINER, "template doesn''t have node: {0}", n.getNodeName());
                } else {
                    if ( t.getNodeValue().equals("*") ) {
                        logger.log(Level.FINER, "template allows any value: {0}", n.getNodeName());
                    } else if ( t.getNodeValue().equals(n.getNodeValue()) ) {
                        logger.log(Level.FINER, "template matches: {0}={1}", new Object[]{n.getNodeName(), n.getNodeValue()});
                    } else {
                        logger.log(Level.INFO, "attribute ''{0}'' of ''{1}'' doesn''t match: template={2}, check ={3}", new Object[]{ t.getNodeName(), template.getNodeName(), t.getNodeValue(), n.getNodeValue()});
                    }
                }
            }
        } else {
            if ( template.hasAttributes() ) {
                logger.log(Level.INFO, "template has attributes where check does not." );
            }
        }
        NodeList kids= getKidsSansCommentsText(check);
        NodeList templateKids= getKidsSansCommentsText(template);
        
        for ( int i=0; i<kids.getLength(); i++ ) {
            Node checkKidNode= kids.item(i);
            Node templateKidNode= null;
            for ( int j=0; j<templateKids.getLength(); j++ ) {
                if ( templateKids.item(j).getNodeName().equals(checkKidNode.getNodeName()) ) {
                    templateKidNode= templateKids.item(j);
                    if ( i!=j ) {
                        logger.log(Level.FINER, "node position changes: {0}!={1}", new Object[]{i, j});
                    }
                    break;
                }
            }
            if ( templateKidNode!=null && !templateKids.item(i).getNodeName().equals(checkKidNode.getNodeName()) ) {
                logger.log(Level.FINER, "node position changes: {0}", new Object[]{i});
            } else if ( templateKidNode!=null ) {
                templateKidNode= templateKids.item(i);
            }
            if (templateKidNode==null ) {
                logger.log(Level.INFO, "can''t find the corresponding node: {0}={1}", new Object[] { checkKidNode.getNodeName(), checkKidNode.getNodeValue() } );
            } else {
                checkTemplate( templateKidNode, checkKidNode );
            }
        }
        return null;
    }
    
    String checkTemplate( Document template, Document check ) {
        return checkTemplate( template.getDocumentElement(), check.getDocumentElement() );

    }
        
    boolean check( String javaws, boolean initial ) throws MalformedURLException, IOException, XPathExpressionException {

        System.err.println( "javaws="+javaws );
        List<String> errors= new ArrayList();

        Document doc= urlToDocument( javaws );

        XPath xp = XPathFactory.newInstance().newXPath();

        String href = (String) xp.evaluate( "/jnlp/@href", doc.getDocumentElement(), javax.xml.xpath.XPathConstants.STRING );
        if ( href.length()==0 ) {
            errors.add("href is not defined in " + javaws + " continuing using this one");
        }
        String codebase= (String) xp.evaluate( "/jnlp/@codebase", doc.getDocumentElement(), javax.xml.xpath.XPathConstants.STRING );
        URL codebaseUrl= new URL( codebase );

        if ( initial && href.length()>0 ) {
            return check( new URL( codebaseUrl,  href ).toString(), false );
        } else {
            if ( href.length()>0 && !javaws.equals(codebase+href) ) {
                errors.add( "After second load, href + codebase is not the same as javaws.  Should be "+javaws );
            }

            NodeList nn= (NodeList) xp.evaluate( "/jnlp/resources/jar/@href", doc.getDocumentElement(), javax.xml.xpath.XPathConstants.NODESET );

            for ( int i=0; i<nn.getLength(); i++ ) {
                boolean haveDsa= false;
                boolean haveSignature= false;
                boolean haveTemplate= false;
                
                String href1= new URL( codebaseUrl, nn.item(i).getTextContent() ).toString();
                System.err.println("loading "+href1);
                URL url1= new URL(href1);

                File localFile= File.createTempFile( "WebStartLint", ".zip" );
                download( url1,localFile );

                ZipFile zip= new ZipFile(localFile);
                ZipInputStream zin= new ZipInputStream( url1.openStream() );

                Map<String,String> signatures= new HashMap();

                ZipEntry en= zin.getNextEntry();
                while ( en!=null ) {
                    if ( en.getName().matches("META-INF/.*\\.DSA") ) {
                        haveDsa= true;
                        System.err.println("found signature: "+en.getName());
                    } else if ( en.getName().matches("META-INF/.*\\.SF") ) {
                        haveSignature= true;
                        System.err.println("found signature: "+en.getName());
                    } else if ( en.getName().matches("JNLP-INF/APPLICATION_TEMPLATE.JNLP") ) {
                        haveTemplate= true;
                    }
                    
                    en= zin.getNextEntry();
                }
                zin.close();

                if ( !haveDsa && !haveSignature ) {
                    errors.add( "Jar is not signed: "+href1 );
                }
                
                if ( haveTemplate ) {
                    Document template= insToDocument( zip.getInputStream( zip.getEntry("JNLP-INF/APPLICATION_TEMPLATE.JNLP") ) );
                    String err= checkTemplate( template, doc );
                    if ( err!=null ) {
                        throw new RuntimeException(err);
                    }
                }
            }

            for ( int i= 0; i<errors.size(); i++ ) {
                System.err.println( errors.get(i) );
            }
            if ( errors.size()==0 ) {
                System.err.println("YOU ROCK, "+javaws +" verifies!");
                return true;
            } else {
                return false;
            }

        }
    }
}
