package tpietzsch.day8;

import bdv.spimdata.SpimDataMinimal;
import bdv.spimdata.XmlIoSpimDataMinimal;
import bdv.volume.LRUBlockCache;
import com.jogamp.opengl.GL3;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLEventListener;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Arrays;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.sequence.MultiResolutionSetupImgLoader;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.util.IntervalIndexer;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import org.joml.Matrix4f;
import tpietzsch.day2.Shader;
import tpietzsch.day4.InputFrame;
import tpietzsch.day4.ScreenPlane1;
import tpietzsch.day4.WireframeBox1;
import tpietzsch.util.MatrixMath;

import static com.jogamp.opengl.GL.GL_BLEND;
import static com.jogamp.opengl.GL.GL_COLOR_BUFFER_BIT;
import static com.jogamp.opengl.GL.GL_DEPTH_BUFFER_BIT;
import static com.jogamp.opengl.GL.GL_DEPTH_TEST;
import static com.jogamp.opengl.GL.GL_LINEAR;
import static com.jogamp.opengl.GL.GL_ONE_MINUS_SRC_ALPHA;
import static com.jogamp.opengl.GL.GL_R16F;
import static com.jogamp.opengl.GL.GL_SRC_ALPHA;
import static com.jogamp.opengl.GL.GL_TEXTURE0;
import static com.jogamp.opengl.GL.GL_TEXTURE_MAG_FILTER;
import static com.jogamp.opengl.GL.GL_TEXTURE_MIN_FILTER;
import static com.jogamp.opengl.GL.GL_UNPACK_ALIGNMENT;
import static com.jogamp.opengl.GL.GL_UNSIGNED_SHORT;
import static com.jogamp.opengl.GL2ES2.GL_RED;
import static com.jogamp.opengl.GL2ES2.GL_TEXTURE_3D;

/**
 * Block TextureCache, just sliced as a plain texture (visible in exaggerated padding overlap)
 */
public class Example1 implements GLEventListener
{
	private final RandomAccessibleInterval< UnsignedShortType > rai;

	private final AffineTransform3D sourceTransform;

	private Shader prog;

	private Shader progvol;

	private WireframeBox1 box;

	private ScreenPlane1 screenPlane;

	private TextureCache textureCache;

	private int[] blockSize;

	private int[] paddedBlockSize;

	public Example1( final RandomAccessibleInterval< UnsignedShortType > rai, final AffineTransform3D sourceTransform )
	{
		this.rai = rai;
		this.sourceTransform = sourceTransform;
	}

	@Override
	public void init( final GLAutoDrawable drawable )
	{
		final GL3 gl = drawable.getGL().getGL3();

		box = new WireframeBox1();
		box.updateVertices( gl, rai );
		screenPlane = new ScreenPlane1();
		screenPlane.updateVertices( gl, new FinalInterval( 640, 480 ) );

		prog = new Shader( gl, "ex1", "ex1" );
		progvol = new Shader( gl, "ex1", "ex1slice" );

		System.out.println( "rai = " + Util.printInterval( rai ) );

		blockSize = new int[] { 32, 32, 32 };
		paddedBlockSize = new int[] { 40, 40, 40 };
		loadTexture( gl );

		gl.glEnable( GL_DEPTH_TEST );
	}

	public static class TextureCache
	{
		private final int[] blockSize;

		private final int[] gridSize;

		private int texture;

		private boolean textureInitialized = false;

		public TextureCache( final int[] blockSize, final int[] gridSize )
		{
			this.blockSize = blockSize.clone();
			this.gridSize = gridSize.clone();
		}

		private void init( GL3 gl )
		{
			if ( textureInitialized )
				return;
			textureInitialized = true;

			final int[] tmp = new int[ 1 ];
			gl.glGenTextures( 1, tmp, 0 );
			texture = tmp[ 0 ];
			gl.glBindTexture( GL_TEXTURE_3D, texture );

			final int w = gridSize[ 0 ] * blockSize[ 0 ];
			final int h = gridSize[ 1 ] * blockSize[ 1 ];
			final int d = gridSize[ 2 ] * blockSize[ 2 ];

			gl.glTexStorage3D( GL_TEXTURE_3D, 1, GL_R16F, w, h, d );
			gl.glTexParameteri( GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR );
			gl.glTexParameteri( GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR );
			gl.glPixelStorei( GL_UNPACK_ALIGNMENT, 2 );
		}

		public void putBlockData( GL3 gl, int[] gridPos, final ByteBuffer data )
		{
			init( gl );

			final int x = gridPos[ 0 ] * blockSize[ 0 ];
			final int y = gridPos[ 1 ] * blockSize[ 1 ];
			final int z = gridPos[ 2 ] * blockSize[ 2 ];
			final int w = blockSize[ 0 ];
			final int h = blockSize[ 1 ];
			final int d = blockSize[ 2 ];

			gl.glBindTexture( GL_TEXTURE_3D, texture );
			gl.glTexSubImage3D( GL_TEXTURE_3D, 0, x, y, z, w, h, d, GL_RED, GL_UNSIGNED_SHORT, data );
		}
	}

	private void getBlock( ByteBuffer buffer, final int ... gridPos )
	{
		Interval interval = Intervals.createMinSize(
				gridPos[ 0 ] * blockSize[ 0 ],
				gridPos[ 1 ] * blockSize[ 1 ],
				gridPos[ 2 ] * blockSize[ 2 ],
				paddedBlockSize[ 0 ],
				paddedBlockSize[ 1 ],
				paddedBlockSize[ 2 ]
		);
		BlockTextureUtils.imgToBuffer( Views.interval( Views.extendZero( rai ), interval ), buffer );
	}

	private void loadTexture( final GL3 gl )
	{

		if ( rai.dimension( 0 ) % 2 != 0 )
			System.err.println( "possible GL_UNPACK_ALIGNMENT problem. glPixelStorei ..." );

//		final int[] gridSize = LRUBlockCache.findSuitableGridSize( paddedBlockSize, 2, 100 );
//		System.out.println( "gridSize = " + Util.printCoordinates( gridSize ) );

		final int[] gridSize = new int[ 3 ];
		for ( int d = 0; d < 3; ++d )
			gridSize[ d ] = ( int ) ( rai.dimension( d ) - 1 ) / blockSize[ d ] + 1;
		textureCache = new TextureCache( paddedBlockSize, gridSize );

//		for ( int i = 0; i < 100; ++i )
//		{
//			long t0 = System.currentTimeMillis();
		final ByteBuffer buffer = BlockTextureUtils.allocateBlockBuffer( paddedBlockSize );
		for ( int z = 0; z < gridSize[ 2 ]; ++z )
			for ( int y = 0; y < gridSize[ 1 ]; ++y )
				for ( int x = 0; x < gridSize[ 0 ]; ++x )
				{
					this.getBlock( buffer, x, y, z );
					textureCache.putBlockData( gl, new int[] { x, y, z }, buffer );
				}
//			long t1 = System.currentTimeMillis();
//			System.out.println( "t = " + ( t1 - t0 ) );
//		}

		System.out.println( "TextureCache Loaded" );
	}

	@Override
	public void dispose( final GLAutoDrawable drawable )
	{
	}

	@Override
	public void display( final GLAutoDrawable drawable )
	{
		final GL3 gl = drawable.getGL().getGL3();

		gl.glClearColor( 0f, 0f, 0f, 1f );
		gl.glClear( GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT );

		final Matrix4f model = new Matrix4f();
		final Matrix4f view = new Matrix4f();
		final Matrix4f projection = new Matrix4f();
		MatrixMath.affine( sourceTransform, model );
		view.set( new float[] { 0.56280f, -0.13956f, 0.23033f, 0.00000f, 0.00395f, 0.53783f, 0.31621f, 0.00000f, -0.26928f, -0.28378f, 0.48603f, 0.00000f, 96.02715f, 211.68768f, -186.46806f, 1.00000f } );
		projection.set( new float[] { 5.40541f, 0.00000f, 0.00000f, 0.00000f, -0.00000f, -6.89655f, -0.00000f, -0.00000f, -0.00000f, -0.00000f, 2.00000f, 1.00000f, -1729.72974f, 1655.17236f, 1000.00000f, 2000.00000f } );
		final Matrix4f ip = new Matrix4f( projection ).invert();
		final Matrix4f ivm = new Matrix4f( view ).mul( model ).invert();

		prog.use( gl );
		prog.setUniform( gl, "model", model );
		prog.setUniform( gl, "view", view );
		prog.setUniform( gl, "projection", projection );

		prog.setUniform( gl, "color", 1.0f, 0.5f, 0.2f, 1.0f );
		box.draw( gl );

		model.identity();
		view.identity();
		progvol.use( gl );
		progvol.setUniform( gl, "model", model );
		progvol.setUniform( gl, "view", view );
		progvol.setUniform( gl, "projection", projection );
		progvol.setUniform( gl, "ip", ip );
		progvol.setUniform( gl, "ivm", ivm );
		progvol.setUniform( gl, "sourcemin", rai.min( 0 ), rai.min( 1 ), rai.min( 2 ) );
		progvol.setUniform( gl, "sourcemax", rai.max( 0 ), rai.max( 1 ), rai.max( 2 ) );
		progvol.setUniform( gl, "volume", 0 );
		progvol.setUniform( gl, "invVolumeSize",
				( float ) ( 1.0 / rai.dimension( 0 ) ),
				( float ) ( 1.0 / rai.dimension( 1 ) ),
				( float ) ( 1.0 / rai.dimension( 2 ) ) );
		gl.glActiveTexture( GL_TEXTURE0 );
		gl.glBindTexture( GL_TEXTURE_3D, textureCache.texture );
		double min = 962;
		double max = 6201;
		double fmin = min / 0xffff;
		double fmax = max / 0xffff;
		double s = 1.0 / ( fmax - fmin );
		double o = -fmin * s;
		progvol.setUniform( gl, "intensity_offset", ( float ) o );
		progvol.setUniform( gl, "intensity_scale", ( float ) s );

		gl.glEnable( GL_BLEND );
		gl.glBlendFunc( GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA );
		screenPlane.draw( gl );
		gl.glDisable( GL_BLEND );
	}

	@Override
	public void reshape( final GLAutoDrawable drawable, final int x, final int y, final int width, final int height )
	{
	}

	public static void main( final String[] args ) throws SpimDataException
	{
		final String xmlFilename = "/Users/pietzsch/workspace/data/111010_weber_full.xml";
		final SpimDataMinimal spimData = new XmlIoSpimDataMinimal().load( xmlFilename );
		MultiResolutionSetupImgLoader< UnsignedShortType > sil = ( MultiResolutionSetupImgLoader< UnsignedShortType > ) spimData.getSequenceDescription().getImgLoader().getSetupImgLoader( 0 );
		final int level = 0; //source.getNumMipmapLevels() - 1;
		final RandomAccessibleInterval< UnsignedShortType > rai = sil.getImage( 1, level );
		final AffineTransform3D sourceTransform = spimData.getViewRegistrations().getViewRegistration( 1, 0 ).getModel();

		final InputFrame frame = new InputFrame( "Example1", 640, 480 );
		InputFrame.DEBUG = false;
		Example1 glPainter = new Example1( rai, sourceTransform );
		frame.setGlEventListener( glPainter );
		frame.show();
	}
}
