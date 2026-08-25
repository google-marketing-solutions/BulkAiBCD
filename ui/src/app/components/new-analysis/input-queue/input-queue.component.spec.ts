import {ComponentFixture, TestBed} from '@angular/core/testing';
import {NoopAnimationsModule} from '@angular/platform-browser/animations';

import {AnalysisVideoFile} from '../../../services/analysis.service';
import {InputQueueComponent} from './input-queue.component';

describe('InputQueueComponent', () => {
  let fixture: ComponentFixture<InputQueueComponent>;
  let component: InputQueueComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [InputQueueComponent, NoopAnimationsModule],
    }).compileComponents();

    fixture = TestBed.createComponent(InputQueueComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('groups files by source type, parses YouTube thumbnails, and passes unlisted flag', () => {
    const ytFile = new File([''], 'https://www.youtube.com/watch?v=dQw4w9WgXcQ', {
      type: 'youtube/url',
    }) as AnalysisVideoFile;
    ytFile.sourceUrl = 'https://www.youtube.com/watch?v=dQw4w9WgXcQ';
    ytFile.unlisted = true;

    const driveFile = new File([''], 'drive-doc.mp4', {type: 'drive/url'});
    const uploadFile = new File([''], 'local-vid.mp4', {type: 'video/uploaded'});

    fixture.componentRef.setInput('videos', [ytFile, driveFile, uploadFile]);
    fixture.detectChanges();

    const groups = component.groups();
    expect(groups.length).toBe(3);
    expect(groups[0].key).toBe('youtube');
    expect(groups[0].items[0].thumbnailUrl).toContain('i.ytimg.com/vi/dQw4w9WgXcQ');
    expect(groups[0].items[0].unlisted).toBeTrue();
    expect(groups[1].key).toBe('drive');
    expect(groups[2].key).toBe('file');
  });

  it('toggles group collapse state', () => {
    expect(component.isCollapsed('youtube')).toBeFalse();
    component.toggleGroup('youtube');
    expect(component.isCollapsed('youtube')).toBeTrue();
  });

  it('emits deleteVideo and clearAll events', () => {
    const deleteSpy = jasmine.createSpy();
    const clearSpy = jasmine.createSpy();

    component.deleteVideo.subscribe(deleteSpy);
    component.clearAll.subscribe(clearSpy);

    component.onDeleteVideo(2);
    expect(deleteSpy).toHaveBeenCalledWith(2);

    component.onClearAll();
    expect(clearSpy).toHaveBeenCalled();
  });
});
