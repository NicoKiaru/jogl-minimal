package tpietzsch.day10;

import bdv.spimdata.SpimDataMinimal;
import bdv.spimdata.XmlIoSpimDataMinimal;
import com.jogamp.opengl.GL3;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLEventListener;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.sequence.MultiResolutionSetupImgLoader;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.iterator.IntervalIterator;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.util.IntervalIndexer;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import org.joml.Matrix4f;
import tpietzsch.day10.LRUBlockCache.TextureBlock;
import tpietzsch.day2.Shader;
import tpietzsch.day4.InputFrame;
import tpietzsch.day4.ScreenPlane1;
import tpietzsch.day4.WireframeBox1;
import tpietzsch.day8.BlockTextureUtils;
import tpietzsch.util.MatrixMath;

import static com.jogamp.opengl.GL.GL_BLEND;
import static com.jogamp.opengl.GL.GL_COLOR_BUFFER_BIT;
import static com.jogamp.opengl.GL.GL_DEPTH_BUFFER_BIT;
import static com.jogamp.opengl.GL.GL_DEPTH_TEST;
import static com.jogamp.opengl.GL.GL_FLOAT;
import static com.jogamp.opengl.GL.GL_LINEAR;
import static com.jogamp.opengl.GL.GL_NEAREST;
import static com.jogamp.opengl.GL.GL_ONE_MINUS_SRC_ALPHA;
import static com.jogamp.opengl.GL.GL_R16F;
import static com.jogamp.opengl.GL.GL_RGB;
import static com.jogamp.opengl.GL.GL_RGB32F;
import static com.jogamp.opengl.GL.GL_SRC_ALPHA;
import static com.jogamp.opengl.GL.GL_TEXTURE0;
import static com.jogamp.opengl.GL.GL_TEXTURE1;
import static com.jogamp.opengl.GL.GL_TEXTURE2;
import static com.jogamp.opengl.GL.GL_TEXTURE_MAG_FILTER;
import static com.jogamp.opengl.GL.GL_TEXTURE_MIN_FILTER;
import static com.jogamp.opengl.GL.GL_UNPACK_ALIGNMENT;
import static com.jogamp.opengl.GL.GL_UNSIGNED_SHORT;
import static com.jogamp.opengl.GL2ES2.GL_RED;
import static com.jogamp.opengl.GL2ES2.GL_TEXTURE_3D;

/**
 * Rendering slices with BlockTexture and TextureCache.
 */
public class Example2 implements GLEventListener
{
	private final List< RaiLevel> raiLevels;

	private final AffineTransform3D sourceTransform;

	private Shader prog;

	private Shader progvol;

	private WireframeBox1 box;

	private ScreenPlane1 screenPlane;

	private TextureCache textureCache;

	private final int[] blockSize = { 32, 32, 32 };

	private final int[] paddedBlockSize = { 34, 34, 34 };

	private final int[] cachePadOffset = { 1, 1, 1 };

	private final int[] imgGridSize;

	private LRUBlockCache< BlockKey > lruBlockCache;

	private LookupTexture lookupTexture;

	public Example2( List< RaiLevel> raiLevels, final AffineTransform3D sourceTransform )
	{
		this.raiLevels = raiLevels;
		this.sourceTransform = sourceTransform;
		imgGridSize = raiLevels.get( 0 ).imgGridSize( blockSize );

//		for ( RaiLevel raiLevel : raiLevels )
//		{
//			final int[] g = raiLevel.imgGridSize( blockSize );
//			System.out.println( "g = " + Arrays.toString( g ) + " = " + Intervals.numElements( g ) );
//		}
	}

	@Override
	public void init( final GLAutoDrawable drawable )
	{
		final GL3 gl = drawable.getGL().getGL3();
		gl.glPixelStorei( GL_UNPACK_ALIGNMENT, 2 );

		box = new WireframeBox1();
		box.updateVertices( gl, raiLevels.get( 0 ).rai );
		screenPlane = new ScreenPlane1();
		screenPlane.updateVertices( gl, new FinalInterval( 640, 480 ) );

		prog = new Shader( gl, "ex1", "ex1" );
		progvol = new Shader( gl, "ex1", "ex1slice" );

		loadTexture( gl );
		buildLookupTexture( gl );

		gl.glEnable( GL_DEPTH_TEST );
	}

	public static class BlockKey
	{
		private final int[] gridPos;

		private final int level;

		private final int hashcode;

		public BlockKey( final int gridX, final int gridY, final int gridZ, final int level )
		{
			this.gridPos = new int[] { gridX, gridY, gridZ };
			this.level = level;

			int h = Arrays.hashCode( gridPos );
			h = 31 * h + level;
			hashcode = h;
		}

		public BlockKey( final int[] gridPos, final int level )
		{
			this( gridPos[ 0 ], gridPos[ 1 ], gridPos[ 2 ], level );
		}

		@Override
		public boolean equals( final Object obj )
		{
			if ( obj instanceof BlockKey )
			{
				final BlockKey b = ( BlockKey ) obj;
				return Arrays.equals( gridPos, b.gridPos )
						&& level == b.level;
			}
			return false;
		}

		@Override
		public int hashCode()
		{
			return hashcode;
		}
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
		}

		public void putBlockData( GL3 gl, TextureBlock textureBlock, final ByteBuffer data )
		{
			init( gl );

			final int[] pos = textureBlock.getPos();
			final int x = pos[ 0 ];
			final int y = pos[ 1 ];
			final int z = pos[ 2 ];
			final int w = blockSize[ 0 ];
			final int h = blockSize[ 1 ];
			final int d = blockSize[ 2 ];

			gl.glBindTexture( GL_TEXTURE_3D, texture );
			gl.glTexSubImage3D( GL_TEXTURE_3D, 0, x, y, z, w, h, d, GL_RED, GL_UNSIGNED_SHORT, data );
		}
	}

	private void getBlockData( ByteBuffer buffer, RandomAccessibleInterval< UnsignedShortType > rai, final int ... gridPos )
	{
		long[] min = new long[ 3 ];
		long[] max = new long[ 3 ];
		for ( int d = 0; d < 3; ++d )
		{
			min[ d ] = gridPos[ d ] * blockSize[ d ] - cachePadOffset[ d ];
			max[ d ] = min[ d ] + paddedBlockSize[ d ] - 1;
		}
		BlockTextureUtils.imgToBuffer( Views.interval( Views.extendZero( rai ), min, max ), buffer );
	}

	private void loadTexture( final GL3 gl )
	{
		lruBlockCache = new LRUBlockCache<>( paddedBlockSize, LRUBlockCache.findSuitableGridSize( paddedBlockSize, 2, 100 ) );
		System.out.println( "lruBlockCache.getGridSize() = " + Arrays.toString( lruBlockCache.getGridSize() ) );
		textureCache = new TextureCache( paddedBlockSize, lruBlockCache.getGridSize() );

		final ByteBuffer buffer = BlockTextureUtils.allocateBlockBuffer( paddedBlockSize );

		final RandomAccessibleInterval< UnsignedShortType > rai0 = raiLevels.get( 0 ).rai;
		final long sx = rai0.dimension( 0 );
		final long sy = rai0.dimension( 1 );
		final long sz = rai0.dimension( 2 );
		for ( RaiLevel raiLevel : raiLevels )
		{
			final RandomAccessibleInterval< UnsignedShortType > rai = raiLevel.rai;
			final int level = raiLevel.level;
			final int[] r = raiLevel.r;
			for ( int z = 0; z * blockSize[ 2 ] * r[ 2 ] < sz; ++z )
				for ( int y = 0; y * blockSize[ 1 ] * r[ 1 ] < sy; ++y )
					for ( int x = 0; x * blockSize[ 0 ] * r[ 0 ] < sx; ++x )
					{
						final BlockKey key = new BlockKey( x, y, z, level );
						final TextureBlock block = lruBlockCache.add( key );
						getBlockData( buffer, rai, x, y, z );
						textureCache.putBlockData( gl, block, buffer );

					}
		}

		System.out.println( "TextureCache Loaded" );
	}

	public static class LookupTexture
	{
		private final int[] size;

		private int textureOffset;

		private int textureScale;

		private boolean textureInitialized = false;

		public LookupTexture( final int[] size )
		{
			this.size = size.clone();
		}

		private void init( GL3 gl )
		{
			if ( textureInitialized )
				return;
			textureInitialized = true;

			final int[] tmp = new int[ 2 ];
			gl.glGenTextures( 2, tmp, 0 );
			textureOffset = tmp[ 0 ];
			textureScale = tmp[ 1 ];

			final int w = size[ 0 ];
			final int h = size[ 1 ];
			final int d = size[ 2 ];

			gl.glBindTexture( GL_TEXTURE_3D, textureOffset );
			gl.glTexStorage3D( GL_TEXTURE_3D, 1, GL_RGB32F, w, h, d );
			gl.glTexParameteri( GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_NEAREST );
			gl.glTexParameteri( GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_NEAREST );

			gl.glBindTexture( GL_TEXTURE_3D, textureScale );
			gl.glTexStorage3D( GL_TEXTURE_3D, 1, GL_RGB32F, w, h, d );
			gl.glTexParameteri( GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_NEAREST );
			gl.glTexParameteri( GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_NEAREST );
		}

		public void set( GL3 gl, final float[] scale, final float[] offset )
		{
			init( gl );

			final int w = size[ 0 ];
			final int h = size[ 1 ];
			final int d = size[ 2 ];

			gl.glBindTexture( GL_TEXTURE_3D, textureScale );
			gl.glTexSubImage3D( GL_TEXTURE_3D, 0, 0, 0, 0, w, h, d, GL_RGB, GL_FLOAT, FloatBuffer.wrap( scale ) );

			gl.glBindTexture( GL_TEXTURE_3D, textureOffset );
			gl.glTexSubImage3D( GL_TEXTURE_3D, 0, 0, 0, 0, w, h, d, GL_RGB, GL_FLOAT, FloatBuffer.wrap( offset ) );
		}
	}

	final int[] padSize = { 1, 1, 1 };

	private void buildLookupTexture( final GL3 gl )
	{
		final int[] lutSize = new int[ 3 ];
		for ( int d = 0; d < 3; ++d )
			lutSize[ d ] = Math.max( 64, imgGridSize[ d ] );
		lookupTexture = new LookupTexture( lutSize );

		final int[] padOffset = new int[ 3 ];
		for ( int d = 0; d < 3; ++d )
			padOffset[ d ] = -padSize[ d ];

		final int[] cacheSize = new int[ 3 ];
		for ( int d = 0; d < 3; ++d )
			cacheSize[ d ] = textureCache.gridSize[ d ] * textureCache.blockSize[ d ];

		final float[] qsData = new float[ 3 * ( int ) Intervals.numElements( lutSize ) ];
		final float[] qdData = new float[ 3 * ( int ) Intervals.numElements( lutSize ) ];

		final IntervalIterator imgGridIter = new IntervalIterator( imgGridSize );
		final int[] g0 = new int[ 3 ];
		final int[] gj = new int[ 3 ];
		int level = 0;
		while ( imgGridIter.hasNext() )
		{
			final double[] sj = raiLevels.get( level ).s;

			imgGridIter.fwd();
			imgGridIter.localize( g0 );
			for ( int d = 0; d < 3; ++d )
				gj[ d ] = ( int ) ( g0[ d ] * sj[ d ] );

//			System.out.println( "gj = " + Arrays.toString( gj ) + ", level = " + level );
			final TextureBlock textureBlock = lruBlockCache.get( new BlockKey( gj, level ) );
			if ( textureBlock == null )
			{
				System.out.println( "gj = " + Arrays.toString( gj ) + ", level = " + level );
				continue;
			}
			final int[] texpos = textureBlock.getPos();

			final int i = IntervalIndexer.positionWithOffsetToIndex( g0, lutSize, padOffset );
			for ( int d = 0; d < 3; ++d )
			{
				double qs = sj[ d ] * lutSize[ d ] * blockSize[ d ] / cacheSize[ d ];
				double p = g0[ d ] * blockSize[ d ];
				double hj = 0.5 * ( sj[ d ] - 1 );
				double c0 = texpos[ d ] + cachePadOffset[ d ] + p * sj[ d ] - gj[ d ] * blockSize[ d ] + hj;
				double qd = ( c0 - sj[ d ] * ( padSize[ d ] * blockSize[ d ] + p ) + 0.5 ) / cacheSize[ d ];
				qsData[ 3 * i + d ] = ( float ) qs;
				qdData[ 3 * i + d ] = ( float ) qd;
			}

			level = ( level + 1 ) % 3;
		}

		lookupTexture.set( gl, qsData, qdData );
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
		final RandomAccessibleInterval< UnsignedShortType > rai = raiLevels.get( 0 ).rai;
		progvol.setUniform( gl, "sourcemin", rai.min( 0 ), rai.min( 1 ), rai.min( 2 ) );
		progvol.setUniform( gl, "sourcemax", rai.max( 0 ), rai.max( 1 ), rai.max( 2 ) );

		progvol.setUniform( gl, "scaleLut", 0 );
		progvol.setUniform( gl, "offsetLut", 1 );
		progvol.setUniform( gl, "volumeCache", 2 );
		gl.glActiveTexture( GL_TEXTURE0 );
		gl.glBindTexture( GL_TEXTURE_3D, lookupTexture.textureScale );
		gl.glActiveTexture( GL_TEXTURE1 );
		gl.glBindTexture( GL_TEXTURE_3D, lookupTexture.textureOffset );
		gl.glActiveTexture( GL_TEXTURE2 );
		gl.glBindTexture( GL_TEXTURE_3D, textureCache.texture );

		progvol.setUniform( gl, "blockSize", blockSize[ 0 ], blockSize[ 1 ], blockSize[ 2 ] );
		progvol.setUniform( gl, "lutSize", lookupTexture.size[ 0 ], lookupTexture.size[ 1 ], lookupTexture.size[ 2 ] );
		progvol.setUniform( gl, "padSize", padSize[ 0 ], padSize[ 1 ], padSize[ 2 ] );

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

	static class RaiLevel
	{
		final int level;

		final int[] r;

		final double[] s;

		final RandomAccessibleInterval< UnsignedShortType > rai;

		public RaiLevel( final int level, final double[] resolution, final RandomAccessibleInterval< UnsignedShortType > rai )
		{
			this.level = level;
			this.r = new int[] { (int) resolution[ 0 ], (int) resolution[ 1 ], (int) resolution[ 2 ]  };
			this.s = new double[] { 1 / resolution[ 0 ], 1 / resolution[ 1 ], 1 / resolution[ 2 ] };
			this.rai = rai;
		}

		public int[] imgGridSize( final int[] blockSize )
		{
			final int[] imgGridSize = new int[ 3 ];
			for ( int d = 0; d < 3; ++d )
				imgGridSize[ d ] = ( int ) ( rai.dimension( d ) - 1 ) / blockSize[ d ] + 1;
			return imgGridSize;
		}

		@Override
		public String toString()
		{
			return "RaiLevel{" +
					"level=" + level +
					", r=" + Arrays.toString( r ) +
					", s=" + Arrays.toString( s ) +
					", rai=" + Util.printInterval( rai ) +
					'}';
		}
	}

	public static void main( final String[] args ) throws SpimDataException
	{
		final String xmlFilename = "/Users/pietzsch/workspace/data/111010_weber_full.xml";
		final SpimDataMinimal spimData = new XmlIoSpimDataMinimal().load( xmlFilename );
		MultiResolutionSetupImgLoader< UnsignedShortType > sil = ( MultiResolutionSetupImgLoader< UnsignedShortType > ) spimData.getSequenceDescription().getImgLoader().getSetupImgLoader( 0 );

		ArrayList< RaiLevel > raiLevels = new ArrayList<>();
		final int numMipmapLevels = sil.numMipmapLevels();
		for ( int level = 0; level < numMipmapLevels; level++ )
		{
			final RandomAccessibleInterval< UnsignedShortType > rai = sil.getImage( 1, level );
			final double[] resolution = sil.getMipmapResolutions()[ level ];
			final RaiLevel raiLevel = new RaiLevel( level, resolution, rai );
			raiLevels.add( raiLevel );
			System.out.println( raiLevel );
		}

		final AffineTransform3D sourceTransform = spimData.getViewRegistrations().getViewRegistration( 1, 0 ).getModel();

		final InputFrame frame = new InputFrame( "Example2", 640, 480 );
		InputFrame.DEBUG = false;
		Example2 glPainter = new Example2( raiLevels, sourceTransform );
		frame.setGlEventListener( glPainter );
		frame.show();
	}
}
