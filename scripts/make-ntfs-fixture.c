/* SPDX-License-Identifier: GPL-2.0-or-later
 * Development-only NTFS-3G fixture writer. Never linked into Sift.
 * Run against a freshly formatted disposable image, never a real volume.
 */
#include <stdio.h>
#include <time.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <ntfs-3g/volume.h>
#include <ntfs-3g/dir.h>
#include <ntfs-3g/attrib.h>
#include <ntfs-3g/unistr.h>
static void fail(const char *what) {perror(what);exit(1);}
static ntfs_inode *create(ntfs_inode *dir,const char *name,mode_t type) {
    ntfschar *unicode=NULL;int n=ntfs_mbstoucs(name,&unicode);
    if(n<0)fail("unicode");
    ntfs_inode *file=ntfs_create(dir,const_cpu_to_le32(0),unicode,n,type);
    free(unicode);if(!file)fail("create");return file;
}
static void write_at(ntfs_inode *file,long offset,const void *bytes,long size) {
    ntfs_attr *data=ntfs_attr_open(file,AT_DATA,AT_UNNAMED,0);
    if(!data)fail("data");
    if(ntfs_attr_pwrite(data,offset,size,bytes)!=size)fail("write");
    ntfs_attr_close(data);
}
static void erase(ntfs_volume *vol,ntfs_inode *dir,ntfs_inode *file,const char *name) {
    ntfschar *unicode=NULL;int n=ntfs_mbstoucs(name,&unicode);
    if(ntfs_delete(vol,name,file,dir,unicode,n))fail("delete");free(unicode);
}
int main(int argc,char **argv) {
    if(argc!=2)return 2;
    ntfs_volume *vol=ntfs_mount(argv[1],NTFS_MNT_EXCLUSIVE);
    if(!vol)fail("mount image");
    ntfs_inode *root=ntfs_pathname_to_inode(vol,NULL,"/");if(!root)fail("root");
    ntfs_inode *dir=create(root,"Projects",S_IFDIR);
    if(ntfs_inode_close_in_dir(dir,root))fail("close dir");
    dir=ntfs_pathname_to_inode(vol,NULL,"/Projects");if(!dir)fail("dir");
    ntfs_inode *small=create(dir,"meeting-notes.txt",S_IFREG);
    const char note[]="These notes were permanently deleted, outside any recycle bin.\n";
    write_at(small,0,note,sizeof(note)-1);
    ntfs_inode *large=create(dir,"fragmented.bin",S_IFREG);
    unsigned char block[4096];for(int i=0;i<4096;i++)block[i]=(i*17+31)%251;
    write_at(large,0,block,sizeof(block));
    ntfs_inode *blocker=create(root,"keep-blocker.bin",S_IFREG);
    for(int i=0;i<16;i++)write_at(blocker,i*4096,block,sizeof(block));
    for(int i=1;i<12;i++)write_at(large,i*4096,block,sizeof(block));
    ntfs_inode *live=create(dir,"keep-live.txt",S_IFREG);
    write_at(live,0,"I am not deleted.\n",18);if(ntfs_inode_close_in_dir(live,dir))fail("close live");
    if(ntfs_inode_close_in_dir(blocker,root))fail("close blocker");
    if(ntfs_inode_close_in_dir(small,dir))fail("close small");
    if(ntfs_inode_close_in_dir(large,dir))fail("close large");
    if(ntfs_inode_close_in_dir(dir,root))fail("close parent");
    ntfs_inode_close(root);if(ntfs_umount(vol,0))fail("unmount writes");
    vol=ntfs_mount(argv[1],NTFS_MNT_EXCLUSIVE);if(!vol)fail("remount");
    dir=ntfs_pathname_to_inode(vol,NULL,"/Projects");if(!dir)fail("reopen parent");
    small=ntfs_pathname_to_inode(vol,NULL,"/Projects/meeting-notes.txt");if(!small)fail("small");
    erase(vol,dir,small,"meeting-notes.txt");
    dir=ntfs_pathname_to_inode(vol,NULL,"/Projects");if(!dir)fail("reopen dir");
    large=ntfs_pathname_to_inode(vol,NULL,"/Projects/fragmented.bin");if(!large)fail("large");
    erase(vol,dir,large,"fragmented.bin");
    if(ntfs_umount(vol,0))fail("unmount");
    return 0;
}
