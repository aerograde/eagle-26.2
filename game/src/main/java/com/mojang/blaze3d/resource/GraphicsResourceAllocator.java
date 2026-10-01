package com.mojang.blaze3d.resource;

public interface GraphicsResourceAllocator {
   GraphicsResourceAllocator UNPOOLED = new GraphicsResourceAllocator() {
      @Override
      public <T> T acquire(final ResourceDescriptor<T> descriptor) {
         T resource = descriptor.allocate();
         try {
            descriptor.prepare(resource);
            return resource;
         } catch (RuntimeException | Error exception) {
            try {
               descriptor.free(resource);
            } catch (RuntimeException | Error cleanupException) {
               exception.addSuppressed(cleanupException);
            }

            throw exception;
         }
      }

      @Override
      public <T> void release(final ResourceDescriptor<T> descriptor, final T resource) {
         descriptor.free(resource);
      }
   };

   <T> T acquire(ResourceDescriptor<T> descriptor);

   <T> void release(ResourceDescriptor<T> descriptor, T resource);
}
